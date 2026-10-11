// Package sender, gomobile ile Android'e bağlanan AirPlay mirroring gönderici
// arayüzüdür. Protokol işini doubletake'ten alınan internal/airplay paketi yapar;
// video, Android MediaCodec'ten PushFrame ile gelir.
//
// gomobile kısıtları gereği sadece basit tipler (string, int, []byte, bool,
// error) ve Listener arayüzü kullanılır.
package sender

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"sync"

	"screenmirror/airplay/internal/airplay"
)

// Listener Kotlin tarafından uygulanır. Callback'ler Go goroutine'lerinden,
// herhangi bir thread'de gelebilir; UI işleri için ana thread'e geçin.
type Listener interface {
	// OnStatus: state = "connecting" | "pairing" | "setup" | "streaming" | "stopped" | "error"
	OnStatus(state string, detail string)
	// OnPinRequired: TV ekranında bir PIN gösteriliyor; kullanıcıdan alıp SubmitPin çağırın.
	OnPinRequired()
	// OnVideoCanvas: alıcının tuval boyutu. Encoder'ı bu tuvale sığacak şekilde başlatın,
	// sonra kareleri PushFrame ile verin.
	OnVideoCanvas(width int, height int)
	// OnNeedKeyframe: encoder'dan hemen bir IDR kare isteyin.
	OnNeedKeyframe()
	// OnCredentials: eşleştirme tamamlandı; json'u kalıcı saklayın ve sonraki
	// oturumda NewSession'a savedCreds olarak verin (PIN tekrar sorulmaz).
	OnCredentials(deviceID string, json string)
}

// SetDebug protokol ayrintili loglarini acar (stderr / logcat).
func SetDebug(on bool) { airplay.SetDebugMode(on) }

// Session tek bir Apple TV'ye tek bir yansıtma oturumudur.
type Session struct {
	dev      airplay.AirPlayDevice
	saved    string
	listener Listener
	source   *airplay.ExternalVideoSource

	pinCh chan string

	mu     sync.Mutex
	cancel context.CancelFunc
	ctx    context.Context
}

// NewSession bir oturum hazırlar (ağ işlemi yapmaz).
// txtLines: NsdManager'dan alınan TXT kayıtları, "anahtar=değer\n" satırları.
// savedCreds: daha önce OnCredentials ile verilen JSON ("" ise yok).
func NewSession(name, ip string, port int, txtLines string, savedCreds string, l Listener) *Session {
	ctx, cancel := context.WithCancel(context.Background())
	return &Session{
		dev:      airplay.DeviceFromTXT(name, ip, port, txtLines),
		saved:    savedCreds,
		listener: l,
		source:   airplay.NewExternalVideoSource(l.OnNeedKeyframe),
		pinCh:    make(chan string, 1),
		ctx:      ctx,
		cancel:   cancel,
	}
}

// SubmitPin OnPinRequired'a cevap olarak kullanıcının girdiği PIN'i iletir.
func (s *Session) SubmitPin(pin string) {
	select {
	case s.pinCh <- pin:
	default:
	}
}

// PushFrame encoder'dan gelen bir access unit'i verir (Annex-B, dört byte start code,
// keyframe'lerde SPS+PPS önde). Güvenli: Run başlamadan önce de çağrılabilir.
func (s *Session) PushFrame(annexB []byte, keyframe bool) {
	s.source.Push(annexB, keyframe)
}

// Stop oturumu sonlandırır; Run döner.
func (s *Session) Stop() {
	s.cancel()
	s.source.Close()
}

// Run bağlanır, eşleşir ve akış bitene kadar bloklar. Ayrı bir thread'de çağırın.
func (s *Session) Run() (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("sender panic: %v", r)
		}
		state, detail := "stopped", ""
		if err != nil && s.ctx.Err() == nil {
			state, detail = "error", err.Error()
		}
		s.listener.OnStatus(state, detail)
		s.source.Close()
	}()

	ctx := s.ctx
	s.listener.OnStatus("connecting", s.dev.Name)

	client, info, err := s.connect(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }()

	s.listener.OnStatus("pairing", "")
	client, info, err = s.pair(ctx, client, info)
	if err != nil {
		return err
	}

	if client.FpEkey == nil {
		if err := client.FairPlaySetup(ctx); err != nil {
			if !errors.Is(err, airplay.ErrFairPlayUnsupported) {
				return fmt.Errorf("FairPlay kurulumu: %w", err)
			}
		}
	}

	s.listener.OnStatus("setup", "")
	cfg := airplay.StreamConfig{
		FPS:        30,
		Bitrate:    8000,
		VideoCodec: airplay.VideoCodecH264,
		NoAudio:    true,
	}
	session, err := client.SetupMirrorWithVideoPreparation(ctx, cfg, func(w, h int) error {
		s.listener.OnVideoCanvas(w, h)
		return nil
	})
	if err != nil {
		return fmt.Errorf("mirror kurulumu: %w", err)
	}
	defer session.Close()

	go func() {
		<-ctx.Done()
		session.Close()
	}()

	s.listener.OnStatus("streaming", info.Name)
	if err := session.StreamFrames(ctx, s.source.Capture(), 0); err != nil && ctx.Err() == nil {
		return fmt.Errorf("akış: %w", err)
	}
	return nil
}

func (s *Session) newClient() *airplay.AirPlayClient {
	return airplay.NewAirPlayClientForDevice(s.dev)
}

func (s *Session) connect(ctx context.Context) (*airplay.AirPlayClient, *airplay.ReceiverInfo, error) {
	client := s.newClient()
	if err := client.Connect(ctx); err != nil {
		return nil, nil, fmt.Errorf("bağlanılamadı: %w", err)
	}
	info, err := client.GetInfo()
	if err != nil {
		_ = client.Close()
		return nil, nil, fmt.Errorf("/info alınamadı: %w", err)
	}
	return client, info, nil
}

// pair, doubletake'in eşleştirme sırasını izler: kayıtlı kimlik -> pair-verify;
// olmazsa PIN gerekiyorsa tam eşleştirme, değilse geçici (transient) eşleştirme.
func (s *Session) pair(ctx context.Context, client *airplay.AirPlayClient, info *airplay.ReceiverInfo) (*airplay.AirPlayClient, *airplay.ReceiverInfo, error) {
	if s.saved != "" {
		var saved airplay.SavedCredentials
		if err := json.Unmarshal([]byte(s.saved), &saved); err == nil && saved.HasPairingCredentials() {
			err := client.RestorePairingCredentials(&saved)
			if err == nil {
				err = client.PairVerify(ctx)
			}
			if err == nil {
				return client, info, nil
			}
			// Kayıtlı kimlik reddedildi: temiz bağlantıyla yeniden eşleş.
			_ = client.Close()
			var cerr error
			client, info, cerr = s.connect(ctx)
			if cerr != nil {
				return nil, nil, cerr
			}
		}
	}

	need := info.RequiredPairingCredential()
	s.listener.OnStatus("pairing", fmt.Sprintf(
		"alici: %s, istenen kimlik=%d (0=yok 1=PIN 2=parola), parola_var=%v, kayitli_kimlik=%v",
		info.Name, need, info.RequiresPassword(), s.saved != ""))
	needPIN := need == airplay.PairingCredentialPIN || need == airplay.PairingCredentialPassword
	if !needPIN {
		err := client.Pair(ctx, "")
		if err == nil {
			s.listener.OnStatus("pairing", "gecici eslestirme basarili (PIN gerekmedi)")
			return client, info, nil
		}
		s.listener.OnStatus("pairing", "gecici eslestirme basarisiz: "+err.Error()+"; PIN akisina geciliyor")
		// Geçici eşleştirme başarısız: temiz bağlantıda PIN akışına geç.
		_ = client.Close()
		var cerr error
		client, info, cerr = s.connect(ctx)
		if cerr != nil {
			return nil, nil, cerr
		}
	}

	_ = client.StartPINDisplay() // hata olsa da kullanıcıdan kod istenir
	s.listener.OnPinRequired()
	var pin string
	select {
	case pin = <-s.pinCh:
	case <-ctx.Done():
		return nil, nil, ctx.Err()
	}
	if pin == "" {
		return nil, nil, errors.New("PIN girilmedi")
	}
	if err := client.Pair(ctx, pin); err != nil {
		return nil, nil, fmt.Errorf("eşleştirme: %w", err)
	}
	s.persist(client, info)
	return client, info, nil
}

func (s *Session) persist(client *airplay.AirPlayClient, info *airplay.ReceiverInfo) {
	if client.PairKeys == nil {
		return
	}
	pub := append([]byte(nil), client.PairKeys.Ed25519Public...)
	priv := ed25519.PrivateKey(client.PairKeys.Ed25519Private)
	creds := airplay.SavedCredentials{
		PairingID:       client.PairingID,
		Ed25519Public:   pub,
		Ed25519Seed:     priv.Seed(),
		PairingProtocol: client.PairingProtocol(),
	}
	b, err := json.Marshal(creds)
	if err != nil {
		return
	}
	s.listener.OnCredentials(info.DeviceID, string(b))
}
