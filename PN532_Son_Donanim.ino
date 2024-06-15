#include <Wire.h>
#include <PN532_I2C.h>
#include <PN532.h>
#include <EEPROM.h>

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
#define UID_SIZE        7 // Maximum UID size for supported cards

PN532_I2C pn532_i2c(Wire);
PN532 nfc(pn532_i2c);

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

  if (readCard()) {
    handleCard(cardUID, uidLength, doorClosed, areaFull);
  }
}

bool readCard() {
  if (nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, cardUID, &uidLength, 1000)) {
    return true;
  }
  
  byte command[] = { 0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00 };
  byte response[32];
  uint8_t responseLength;
  
  if (nfc.inDataExchange(command, sizeof(command), response, &responseLength)) {
    Serial.print("APDU Response: ");
    for (uint8_t i = 0; i < responseLength; i++) {
      Serial.print(response[i], HEX);
      Serial.print(" ");
    }
    Serial.println();
    memcpy(cardUID, response, uidLength);
    return true;
  }
  return false;
}

void handleCard(byte cardUID[], byte uidLength, bool doorClosed, bool areaFull) {
  Serial.print("Kart UID: ");
  for (byte i = 0; i < uidLength; i++) {
    Serial.print(cardUID[i] < 0x10 ? " 0" : " ");
    Serial.print(cardUID[i], HEX);
  }
  Serial.println();

  if (isCardRegistered(cardUID, uidLength)) {
    Serial.println("Kart kayıtlı.");
    if (!(doorClosed && areaFull)) {
      openDoor(); // Kart kayıtlı ve alan uygun ise kapıyı açar
    } else {
      Serial.println("Kapı açılamaz. Alan dolu veya kapı kapalı.");
    }
  } else {
    Serial.println("Kart kayıtlı değil. Kapı açılamaz.");
  }
  delay(1000); // Bir sonraki okuma için bekler
}

void checkForAdminCard() {
  if (readCard() && compareUID(cardUID, masterKey, uidLength)) {
    Serial.println("Admin kartı tanımlandı. Admin moduna geçiliyor.");
    adminMode = true;
    delay(3000); // 3 saniye bekler
  }
}

void checkForNewCard() {
  Serial.println("Yeni kartı okut");
  unsigned long startTime = millis();
  while (millis() - startTime < 10000) { // Yeni kart okutmak için 10 saniye bekler
    if (readCard()) {
      byte newCardUID[UID_SIZE];
      memcpy(newCardUID, cardUID, uidLength);

      if (!isCardRegistered(newCardUID, uidLength)) {
        registerNewCard(newCardUID, uidLength); // Kart kayıtlı değilse, yeni kartı kaydeder
        Serial.println("Kart kayıt başarılı");
      } else {
        Serial.println("Bu kart zaten kayıtlı");
      }
      adminMode = false; // Yeni kart okutulduktan sonra admin modundan çıkar
      delay(2000); // Mesajı 2 saniye gösterir
      return;
    }
  }
  Serial.println("Admin modu zaman aşımına uğradı. Admin modundan çıkılıyor.");
  adminMode = false; // Zaman aşımına uğradıysa admin modundan çıkar
}

bool isCardRegistered(byte cardUID[], byte uidLength) {
  for (int i = EEPROM_START; i < EEPROM_SIZE; i += UID_SIZE) {
    bool match = true;
    for (byte j = 0; j < uidLength; j++) {
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

void registerNewCard(byte cardUID[], byte uidLength) {
  if (compareUID(cardUID, masterKey, uidLength)) {
    Serial.println("Admin kartı kaydedilemez.");
    return;
  }

  for (int i = EEPROM_START; i < EEPROM_SIZE; i += UID_SIZE) {
    bool empty = true;
    for (byte j = 0; j < uidLength; j++) {
      if (EEPROM.read(i + j) != 0xFF) {
        empty = false;
        break;
      }
    }
    if (empty) {
      for (byte j = 0; j < uidLength; j++) {
        EEPROM.write(i + j, cardUID[j]);
      }
      Serial.print("Yeni kart kaydedildi - UID: ");
      printCardUID(cardUID, uidLength);
      return;
    }
  }
  Serial.println("Yeni kart kaydetmek için boş slot yok.");
}

bool compareUID(byte* uid1, byte* uid2, byte uidLength) {
  for (byte i = 0; i < uidLength; i++) {
    if (uid1[i] != uid2[i]) {
      return false;
    }
  }
  return true;
}

void printCardUID(byte uid[], byte uidLength) {
  for (byte i = 0; i < uidLength; i++) {
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
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  long duration = pulseIn(ECHO_PIN, HIGH);
  long distance = duration * 0.034 / 2;

  return distance;
}

void resetRFIDModule() {
  nfc.begin();
  nfc.SAMConfig();
}
