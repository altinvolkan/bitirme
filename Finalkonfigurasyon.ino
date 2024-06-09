#include <Adafruit_PN532.h>
#include <EEPROM.h>
#include <NewPing.h>

// PN532 RFID Modülü için pin tanımlamaları
#define SDA_PIN         20
#define SCL_PIN         21
#define RELAY_PIN       7
#define TRIG_PIN        8
#define ECHO_PIN        11
#define BUTTON_PIN_1    12
#define BUTTON_PIN_2    13
#define BUTTON_PIN_3    A0
#define LED_PIN_1       A1
#define LED_PIN_2       A2
#define LED_PIN_3       A3
#define LED_PIN_4       A4
#define RELAY_TIME      3000
#define WAIT_TIME       15000
#define EEPROM_SIZE     4096
#define EEPROM_START    0
#define UID_SIZE        7 // 4 byte UID + 3 byte padding

Adafruit_PN532 nfc(SDA_PIN, SCL_PIN);
NewPing sonar(TRIG_PIN, ECHO_PIN, 200); // 200 cm maksimum mesafe

bool adminMode = false;
byte masterKey[UID_SIZE] = {0xA3, 0x17, 0x1F, 0xF8, 0x00, 0x00, 0x00};
byte cardUID[UID_SIZE];
uint8_t uidLength;

void setup() {
  Serial.begin(9600);
  nfc.begin();
  nfc.SAMConfig();

  pinMode(RELAY_PIN, OUTPUT);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  pinMode(BUTTON_PIN_1, INPUT_PULLUP);
  pinMode(BUTTON_PIN_2, INPUT_PULLUP);
  pinMode(BUTTON_PIN_3, INPUT_PULLUP);
  pinMode(LED_PIN_1, OUTPUT);
  pinMode(LED_PIN_2, OUTPUT);
  pinMode(LED_PIN_3, OUTPUT);
  pinMode(LED_PIN_4, OUTPUT);

  digitalWrite(RELAY_PIN, HIGH);

  Serial.println("Admin kart ID: A3:17:1F:F8");
}

void loop() {
  bool doorClosed = digitalRead(BUTTON_PIN_1) == LOW; // Kapının kapalı olup olmadığını kontrol eder
  bool areaFull = getDistance() < 100; // Alanın dolu olup olmadığını kontrol eder (100 cm mesafe sınırı)

  if (adminMode) {
    digitalWrite(LED_PIN_4, HIGH); // Admin modu aktifse LED4'ü yakar
    digitalWrite(LED_PIN_1, LOW);
    digitalWrite(LED_PIN_2, LOW);
    digitalWrite(LED_PIN_3, LOW);
  } else {
    digitalWrite(LED_PIN_4, LOW); // Admin modu değilse LED4'ü kapatır
  }

  digitalWrite(LED_PIN_1, areaFull ? HIGH : LOW); // Alan doluysa LED1'i yakar, değilse kapatır
  digitalWrite(LED_PIN_2, doorClosed ? HIGH : LOW); // Kapı kapalıysa LED2'yi yakar, değilse kapatır

  if (doorClosed && areaFull) {
    digitalWrite(LED_PIN_3, LOW); // Hem kapı kapalı hem de alan doluysa LED3'ü kapatır
    Serial.println("Tüm alanlar dolu, lütfen bekleyiniz");
  } else {
    digitalWrite(LED_PIN_3, (!doorClosed || !areaFull) ? HIGH : LOW); // Diğer durumlarda LED3'ü yakar veya kapatır
  }

  if (digitalRead(BUTTON_PIN_3) == LOW) {
    clearEEPROM(); // Button 3'e basıldığında EEPROM'u temizler
    while (digitalRead(BUTTON_PIN_3) == LOW); // Button 3 basılı olduğu sürece bekler
  }

  if (digitalRead(BUTTON_PIN_2) == LOW) {
    openDoorFor2Seconds(); // Button 2'ye basıldığında kapıyı 2 saniye açar
    while (digitalRead(BUTTON_PIN_2) == LOW); // Button 2 basılı olduğu sürece bekler
  }

  if (adminMode) {
    checkForNewCard(); // Admin modunda yeni kart kontrolü yapar
  } else {
    checkForAdminCard(); // Normal modda admin kart kontrolü yapar
  }

  if (nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, cardUID, &uidLength)) {
    for (byte i = 0; i < uidLength; i++) {
      cardUID[i] = nfc.uid.uidByte[i];
    }

    // Kalan byte'ları 0x00 ile doldur
    for (byte i = uidLength; i < UID_SIZE; i++) {
      cardUID[i] = 0x00;
    }

    if (isCardRegistered(cardUID)) {
      Serial.println("Kart kayıtlı.");
      if (!(doorClosed && areaFull)) {
        openDoor(); // Kart kayıtlı ve alan uygun ise kapıyı açar
      } else {
        Serial.println("Kapı açılamaz. Alan dolu veya kapı kapalı.");
      }
    } else {
      Serial.println("Kart kayıtlı değil. Kapı açılamaz.");
    }
    resetRFIDModule(); // RFID modülünü resetler
  }
}

void checkForAdminCard() {
  if (nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, cardUID, &uidLength)) {
    // Kalan byte'ları 0x00 ile doldur
    for (byte i = uidLength; i < UID_SIZE; i++) {
      cardUID[i] = 0x00;
    }

    if (compareUID(cardUID, masterKey)) {
      Serial.println("Admin kartı tanımlandı. Admin moduna geçiliyor.");
      adminMode = true;
      delay(3000); // 3 saniye bekler
    }
  }
}

void checkForNewCard() {
  Serial.println("Yeni kartı okut");
  unsigned long startTime = millis();
  while (millis() - startTime < 10000) { // Yeni kart okutmak için 10 saniye bekler
    if (nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, cardUID, &uidLength)) {
      byte newCardUID[UID_SIZE];
      for (byte i = 0; i < uidLength; i++) {
        newCardUID[i] = nfc.uid.uidByte[i];
      }

      // Kalan byte'ları 0x00 ile doldur
      for (byte i = uidLength; i < UID_SIZE; i++) {
        newCardUID[i] = 0x00;
      }

      if (!isCardRegistered(newCardUID)) {
        registerNewCard(newCardUID); // Kart kayıtlı değilse, yeni kartı kaydeder
        Serial.println("Kart kayıt başarılı");
      } else {
        Serial.println("Bu kart zaten kayıtlı");
      }
      adminMode = false; // Yeni kart okutulduktan sonra admin modundan çıkar
      delay(2000); // Mesajı 2 saniye gösterir
      resetRFIDModule(); // RFID modülünü resetler
      return;
    }
  }
  Serial.println("Admin modu zaman aşımına uğradı. Admin modundan çıkılıyor.");
  adminMode = false; // Zaman aşımına uğradıysa admin modundan çıkar
  resetRFIDModule(); // RFID modülünü resetler
}

bool isCardRegistered(byte cardUID[]) {
  for (int i = EEPROM_START; i < EEPROM_SIZE; i += UID_SIZE) {
    bool match = true;
    for (byte j = 0; j < UID_SIZE; j++) {
      if (EEPROM.read(i + j) != cardUID[j]) {
        match = false;
        break;
      }
    }
    if (match) {
      return true;
    }
  }
  return false;
}

void registerNewCard(byte cardUID[]) {
  // Admin kartını kontrol et
  if (compareUID(cardUID, masterKey)) {
    Serial.println("Admin kartı kaydedilemez.");
    return;
  }

  for (int i = EEPROM_START; i < EEPROM_SIZE; i += UID_SIZE) {
    bool empty = true;
    for (byte j = 0; j < UID_SIZE; j++) {
      if (EEPROM.read(i + j) != 0xFF) { // Slot boş olup olmadığını kontrol et
        empty = false;
        break;
      }
    }
    if (empty) {
      for (byte j = 0; j < UID_SIZE; j++) {
        EEPROM.write(i + j, cardUID[j]); // Boş bir slota yeni kartı kaydet
      }
      Serial.print("Yeni kart kaydedildi - UID: ");
      printCardUID(cardUID);
      return;
    }
  }
  Serial.println("Yeni kart kaydetmek için boş slot yok.");
}

bool compareUID(byte* uid1, byte* uid2) {
  for (byte i = 0; i < UID_SIZE; i++) {
    if (uid1[i] != uid2[i]) {
      return false;
    }
  }
  return true;
}

void printCardUID(byte uid[]) {
  for (byte i = 0; i < UID_SIZE; i++) {
    Serial.print(uid[i] < 0x10 ? " 0" : " ");
    Serial.print(uid[i], HEX);
  }
  Serial.println();
}

void openDoor() {
  digitalWrite(RELAY_PIN, LOW); // Röleyi aktive eder (LOW röleyi tetikler)
  delay(RELAY_TIME);
  digitalWrite(RELAY_PIN, HIGH); // Röleyi deaktive eder
  resetRFIDModule();
}

void openDoorFor2Seconds() {
  digitalWrite(RELAY_PIN, LOW); // Röleyi aktive eder (LOW röleyi tetikler)
  delay(2000); // Kapıyı 2 saniye açık tutar
  digitalWrite(RELAY_PIN, HIGH); // Röleyi deaktive eder
  resetRFIDModule();
}

void clearEEPROM() {
  for (int i = EEPROM_START; i < EEPROM_SIZE; i++) {
    EEPROM.write(i, 0xFF); // EEPROM'u temizler
  }
  Serial.println("EEPROM temizlendi.");
}

long getDistance() {
  return sonar.ping_cm();
}

void resetRFIDModule() {
  nfc.begin();
  nfc.SAMConfig();
}
