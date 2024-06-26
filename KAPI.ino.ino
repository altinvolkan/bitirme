#include <SPI.h>
#include <MFRC522.h>
#include <EEPROM.h>

#define RST_PIN         9           // RC522 reset pin
#define SS_PIN          10          // RC522 SS pin
#define RELAY_PIN       7           // Relay pin
#define TRIG_PIN        8           // Ultrasonic sensor TRIG pin
#define ECHO_PIN        11          // Ultrasonic sensor ECHO pin
#define BUTTON_PIN_1    12          // Button 1 pin
#define BUTTON_PIN_2    13          // Button 2 pin
#define BUTTON_PIN_3    A0          // Button 3 pin
#define LED_PIN_1       A1          // LED 1 pin (Ultrasonik Sensör)
#define LED_PIN_2       A2          // LED 2 pin (Kapı Kapalı)
#define LED_PIN_3       A3          // LED 3 pin (Alan Müsait)
#define LED_PIN_4       A4          // LED 4 pin (Kart Kayıt Modu)
#define MAX_PEOPLE      2           // Max number of people allowed inside
#define RELAY_TIME      3000        // Relay activation time (ms)
#define WAIT_TIME       15000       // Time to wait before assuming person has left (ms)
#define EEPROM_SIZE     4096         // EEPROM size in bytes
#define EEPROM_START    0           // Starting address of EEPROM storage for card UIDs
#define UID_SIZE        4           // Size of UID in bytes

MFRC522 mfrc522(SS_PIN, RST_PIN);   // Create MFRC522 instance
bool adminMode = false;             // Admin mode flag

byte masterKey[UID_SIZE] = {0xA3, 0x17, 0x1F, 0xF8}; // Master key definition

void setup() {
  Serial.begin(9600);
  SPI.begin();
  mfrc522.PCD_Init();

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

  digitalWrite(RELAY_PIN, HIGH); // Ensure relay is off
  Serial.println("Admin card ID: A3:17:1F:F8");
}

void loop() {
  bool doorClosed = digitalRead(BUTTON_PIN_1) == LOW;
  bool areaFull = getDistance() < 100;

  // LED 1: Ultrasonik Sensör Algılaması
  digitalWrite(LED_PIN_1, areaFull ? HIGH : LOW);

  // LED 2: Kapı Kapalı
  digitalWrite(LED_PIN_2, doorClosed ? HIGH : LOW);

  // LED 3: Alan Müsait
  if (doorClosed && areaFull) {
    digitalWrite(LED_PIN_3, LOW); // Turn off LED 3 if both conditions are met
    Serial.println("Tüm alanlar dolu, lütfen bekleyiniz");
  } else {
    digitalWrite(LED_PIN_3, (!doorClosed || !areaFull) ? HIGH : LOW); // LED 3 if area is available
  }

  // LED 4: Kart Kayıt Modu
  if (adminMode) {
    digitalWrite(LED_PIN_4, millis() % 1000 < 500 ? HIGH : LOW); // Blink LED 4 in admin mode
  } else {
    digitalWrite(LED_PIN_4, LOW);
  }

  if (digitalRead(BUTTON_PIN_3) == LOW) {
    clearEEPROM();
    while (digitalRead(BUTTON_PIN_3) == LOW); // Wait until button is released
  }

  if (digitalRead(BUTTON_PIN_2) == LOW) {
    openDoorFor2Seconds();
    while (digitalRead(BUTTON_PIN_2) == LOW); // Wait until button is released
  }

  if (adminMode) {
    checkForNewCard();
  } else {
    checkForAdminCard();
  }

  if (mfrc522.PICC_IsNewCardPresent() && mfrc522.PICC_ReadCardSerial()) {
    byte cardUID[UID_SIZE];
    for (byte i = 0; i < mfrc522.uid.size; i++) {
      cardUID[i] = mfrc522.uid.uidByte[i];
    }

    if (isCardRegistered(cardUID)) {
      Serial.println("Card is registered.");
      if (!(doorClosed && areaFull)) { // Allow entry if both zones are not full
        openDoor();
      } else {
        Serial.println("Door cannot be opened. Area full or door closed.");
      }
    } else {
      Serial.println("Card not registered. Door cannot be opened.");
    }
  }
}

void checkForAdminCard() {
  if (mfrc522.PICC_IsNewCardPresent() && mfrc522.PICC_ReadCardSerial()) {
    if (compareUID(mfrc522.uid.uidByte, masterKey)) {
      Serial.println("Admin card authenticated. Entering admin mode.");
      adminMode = true;
      delay(3000); // Wait for 3 seconds
    }
  }
}

void checkForNewCard() {
  Serial.println("Yeni kartı okut");
  unsigned long startTime = millis();
  while (millis() - startTime < 10000) { // 10 seconds window to read new card
    if (mfrc522.PICC_IsNewCardPresent() && mfrc522.PICC_ReadCardSerial()) {
      byte newCardUID[UID_SIZE];
      for (byte i = 0; i < mfrc522.uid.size; i++) {
        newCardUID[i] = mfrc522.uid.uidByte[i];
      }

      if (!isCardRegistered(newCardUID)) {
        registerNewCard(newCardUID);
        Serial.println("Kart kayıt başarılı");
      } else {
        Serial.println("Bu kart sistemde var");
      }
      adminMode = false; // Exit admin mode after handling a new card
      delay(2000); // Display message for 2 seconds
      return;
    }
  }
  Serial.println("Admin mode timeout. Exiting admin mode.");
  adminMode = false; // Exit admin mode after timeout
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
  for (int i = EEPROM_START; i < EEPROM_SIZE; i += UID_SIZE) {
    bool empty = true;
    for (byte j = 0; j < UID_SIZE; j++) {
      if (EEPROM.read(i + j) != 0xFF) { // Check if slot is empty
        empty = false;
        break;
      }
    }
    if (empty) {
      for (byte j = 0; j < UID_SIZE; j++) {
        EEPROM.write(i + j, cardUID[j]);
      }
      Serial.print("New card registered - UID: ");
      printCardUID(cardUID);
      return;
    }
  }
  Serial.println("No empty slots available to register new card.");
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
  digitalWrite(RELAY_PIN, LOW); // Activate relay (assuming LOW triggers the relay)
  delay(RELAY_TIME);
  digitalWrite(RELAY_PIN, HIGH); // Deactivate relay
}

void openDoorFor2Seconds() {
  digitalWrite(RELAY_PIN, LOW); // Activate relay (assuming LOW triggers the relay)
  delay(2000); // Keep door open for 2 seconds
  digitalWrite(RELAY_PIN, HIGH); // Deactivate relay
}

void clearEEPROM() {
  for (int i = EEPROM_START; i < EEPROM_SIZE; i++) {
    EEPROM.write(i, 0xFF);
  }
  Serial.println("EEPROM cleared.");
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
