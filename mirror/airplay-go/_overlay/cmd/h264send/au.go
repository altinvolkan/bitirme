package main

import "bytes"

var audPattern = []byte{0, 0, 1, 9}

// nextAccessUnit: iki ardisik AUD (NAL tipi 9) arasini bir access unit sayar
// (ffmpeg'e aud=1 verilmeli). Hem 3 hem 4 byte start code'u destekler: x264 slice'lar
// icin kisa (00 00 01), SPS/PPS/AUD icin uzun (00 00 00 01) kod kullanir.
func nextAccessUnit(b []byte) (au, rest []byte, ok bool) {
	first := bytes.Index(b, audPattern)
	if first < 0 {
		return nil, b, false
	}
	if first > 0 && b[first-1] == 0 {
		first--
	}
	next := bytes.Index(b[first+4:], audPattern)
	if next < 0 {
		return nil, b, false
	}
	end := first + 4 + next
	if b[end-1] == 0 {
		end--
	}
	return b[first:end], b[end:], true
}

// isKey: access unit bir IDR slice (NAL tipi 5) iceriyor mu.
func isKey(au []byte) bool {
	for i := 0; i+3 < len(au); i++ {
		if au[i] == 0 && au[i+1] == 0 && au[i+2] == 1 && au[i+3]&0x1f == 5 {
			return true
		}
	}
	return false
}
