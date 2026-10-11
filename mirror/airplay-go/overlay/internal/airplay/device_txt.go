package airplay

// Screen Mirror overlay (bu dosya upstream doubletake'in parçası değildir).

import "strings"

// DeviceFromTXT, Android NsdManager'dan gelen bilgilerle bir AirPlayDevice
// kurar. txtLines "anahtar=değer" satırlarından oluşur (satır sonu \n).
func DeviceFromTXT(name, ip string, port int, txtLines string) AirPlayDevice {
	txt := make(map[string]string)
	for _, line := range strings.Split(txtLines, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		k, v, _ := strings.Cut(line, "=")
		txt[k] = v
	}
	dev := AirPlayDevice{Name: name, IP: ip, Port: port}
	populateDeviceFromTXT(&dev, txt)
	return dev
}
