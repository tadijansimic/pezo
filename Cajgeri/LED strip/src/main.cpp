#include <Arduino.h>
#include <ArduinoJson.h>
#include <EEPROM.h>
#include <ESP8266WebServer.h>
#include <ESP8266WiFi.h>
#include <FastLED.h>
#include <WiFiUdp.h>

#include "config.h"

// LED strip
CRGB currentStrip[WS2812B_COUNT], targetStrip[WS2812B_COUNT],
    startStrip[WS2812B_COUNT];
CRGB color1 = {255, 0, 0};
CRGB color2 = {0, 0, 255};
uint8_t currentBrightness = 0, targetBrightness = 255, startBrightness = 0;
uint8_t mode = 0;
bool connected = false;
WiFiUDP Udp;

JsonDocument doc;

ESP8266WebServer server(80);

#define stepDelay 25
const uint8_t steps = 60;
uint8_t currentStep = 0;

#define RTC_FLAG_VALUE 0xA5A5A5A5
#define RTC_FLAG_ADDR 0

// RTC flag helpers
uint32_t readRtcFlag() {
  uint32_t flag = 0;
  ESP.rtcUserMemoryRead(RTC_FLAG_ADDR, &flag, sizeof(flag));
  return flag;
}

void writeRtcFlag(uint32_t flag) {
  ESP.rtcUserMemoryWrite(RTC_FLAG_ADDR, &flag, sizeof(flag));
}

// EEPROM helpers
void writeCRGB(int index, const CRGB& color) {
  EEPROM.write(index, color.r);
  EEPROM.write(index + 1, color.g);
  EEPROM.write(index + 2, color.b);
}

CRGB readCRGB(int index) {
  CRGB color;
  color.r = EEPROM.read(index);
  color.g = EEPROM.read(index + 1);
  color.b = EEPROM.read(index + 2);
  return color;
}

void writeUint(int index, uint8_t value) { EEPROM.write(index, value); }
uint8_t readUint(int index) { return EEPROM.read(index); }

//
void fillTargetStrip() {
  for (int i = 0; i < DISPLAY_COUNT; i++) {
    startStrip[displays[i]] = currentStrip[displays[i]];
    targetStrip[displays[i]] = CRGB(color1.r, color1.g, color1.b);
  }
  float t = 0.0f;
  switch (mode) {
    case 0:
      for (int i = 0; i < NON_DISPLAY_COUNT; i++) {
        startStrip[arrangement[i]] = currentStrip[arrangement[i]];
        t = (float)(i) / (NON_DISPLAY_COUNT - 1);
        t = 3 * (float)pow(t, 2) - 2 * (float)pow(t, 3);
        targetStrip[arrangement[i]] =
            CRGB(color1.r + (color2.r - color1.r) * t,
                 color1.g + (color2.g - color1.g) * t,
                 color1.b + (color2.b - color1.b) * t);
      }
      break;
    case 1:
      for (int i = 0; i < NON_DISPLAY_COUNT / 2; i++) {
        t = (float)(i) / (NON_DISPLAY_COUNT / 2 - 1);
        t = 3 * (float)pow(t, 2) - 2 * (float)pow(t, 3);
        startStrip[arrangement[i]] = currentStrip[arrangement[i]];
        targetStrip[arrangement[i]] =
            CRGB(color1.r + (color2.r - color1.r) * t,
                 color1.g + (color2.g - color1.g) * t,
                 color1.b + (color2.b - color1.b) * t);
      }
      for (int i = 0; i < NON_DISPLAY_COUNT / 2; i++) {
        t = (float)(i) / (NON_DISPLAY_COUNT / 2 - 1);
        t = 1 - 3 * (float)pow(t, 2) + 2 * (float)pow(t, 3);
        startStrip[arrangement[i + NON_DISPLAY_COUNT / 2]] =
            currentStrip[arrangement[i + NON_DISPLAY_COUNT / 2]];
        targetStrip[arrangement[i + NON_DISPLAY_COUNT / 2]] =
            CRGB(color1.r + (color2.r - color1.r) * t,
                 color1.g + (color2.g - color1.g) * t,
                 color1.b + (color2.b - color1.b) * t);
      }
      break;
    case 2:
      startStrip[arrangement[3]] = currentStrip[arrangement[3]];
      startStrip[arrangement[4]] = currentStrip[arrangement[4]];
      targetStrip[arrangement[3]] = targetStrip[arrangement[4]] =
          CRGB(color1.r + (color2.r - color1.r) * 0.5f,
               color1.g + (color2.g - color1.g) * 0.5f,
               color1.b + (color2.b - color1.b) * 0.5f);
      for (int i = 0; i < 3; i++) {
        t = (float)(i) / 2.0f;
        startStrip[i] = currentStrip[i];
        targetStrip[arrangement[i]] =
            CRGB(color1.r + (color2.r - color1.r) * t,
                 color1.g + (color2.g - color1.g) * t,
                 color1.b + (color2.b - color1.b) * t);
      }
      for (int i = 0; i < 3; i++) {
        t = (float)(i) / 2.0f;
        startStrip[i + 5] = currentStrip[i + 5];
        targetStrip[arrangement[i + 5]] =
            CRGB(color1.r + (color2.r - color1.r) * t,
                 color1.g + (color2.g - color1.g) * t,
                 color1.b + (color2.b - color1.b) * t);
      }
      break;
    default:
      break;
  }
}

// Web server handlers
void handleRoot() {
  server.send(200, "text/html", "ESP8266 LED Controller Online!");
}

void handleColor() {
  if (server.method() == HTTP_GET) {
    if (!connected) connected = true;
    String response =
        "{"
        "\"brightness\":" +
        String(targetBrightness) +
        ","
        "\"mode\":" +
        String(mode) +
        ","
        "\"color1\":{\"r\":" +
        String(color1.r) + ",\"g\":" + String(color1.g) +
        ",\"b\":" + String(color1.b) +
        "},"
        "\"color2\":{\"r\":" +
        String(color2.r) + ",\"g\":" + String(color2.g) +
        ",\"b\":" + String(color2.b) +
        "}"
        "}";
    server.send(200, "application/json", response);
  } else if (server.method() == HTTP_POST) {
    if (server.hasArg("plain")) {
      String body = server.arg("plain");
      DeserializationError error = deserializeJson(doc, body);
      if (error) {
        server.send(400, "text/plain", "Invalid JSON");
        return;
      }

      mode = doc["mode"].as<uint8_t>();
      targetBrightness = doc["brightness"].as<uint8_t>();
      startBrightness = currentBrightness;
      writeUint(ADDR_MODE, mode);
      writeUint(ADDR_BRIGHTNESS, targetBrightness);

      color1.r = constrain(doc["color1"]["r"].as<int>(), 0, 255);
      color1.g = constrain(doc["color1"]["g"].as<int>(), 0, 255);
      color1.b = constrain(doc["color1"]["b"].as<int>(), 0, 255);
      color2.r = constrain(doc["color2"]["r"].as<int>(), 0, 255);
      color2.g = constrain(doc["color2"]["g"].as<int>(), 0, 255);
      color2.b = constrain(doc["color2"]["b"].as<int>(), 0, 255);

      writeCRGB(ADDR_COLOR1, color1);
      writeCRGB(ADDR_COLOR2, color2);

      EEPROM.commit();
      server.send(200, "text/plain", "Settings updated");

      currentStep = 0;
      fillTargetStrip();
    } else {
      server.send(200, "text/plain", "No valid data");
    }
  } else {
    server.send(405, "text/plain", "Method Not Allowed");
  }
}

void handleReboot() {
  server.send(200, "text/plain", "Rebooting...");
  delay(100);
  ESP.restart();
}

unsigned long lastUpdate = 0;

void setup() {
  
  Serial.begin(115200);
  EEPROM.begin(EEPROM_SIZE);
  
  uint32_t rtcFlag = readRtcFlag();
  Serial.print("RTC flag: 0x");
  Serial.println(rtcFlag, HEX);
  if (rtcFlag != RTC_FLAG_VALUE) {
    writeRtcFlag(RTC_FLAG_VALUE);
    delay(100);
    ESP.restart();
  }

  targetBrightness = readUint(ADDR_BRIGHTNESS);
  mode = readUint(ADDR_MODE);
  color1 = readCRGB(ADDR_COLOR1);
  color2 = readCRGB(ADDR_COLOR2);

  FastLED.addLeds<NEOPIXEL, WS2812B_PIN>(currentStrip, WS2812B_COUNT);
  delay(200);

  fillTargetStrip();
  for (int i = 0; i < WS2812B_COUNT; i++) {
    currentStrip[i] = startStrip[i] = targetStrip[i];
  }
  FastLED.show();
  delay(10);
  FastLED.show();

  for (uint16_t i = 1; i <= targetBrightness; i++) {
    float t = (float)i / targetBrightness;
    t = 3 * (float)pow(t, 2) - 2 *
        (float)pow(t, 3);  // ease-in-ease-out
    currentBrightness = (uint8_t)(t * targetBrightness);
    FastLED.setBrightness(currentBrightness);
    FastLED.show();
    delay(10);
  }
  startBrightness = currentBrightness;

  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);

  Serial.println('a');

  Serial.println("Starting ESP8266 Web Server...");
  WiFi.mode(WIFI_STA);
  if (!WiFi.config(LOCAL_IP, GATEWAY, SUBNET)) {
    for (int i = 0; i < 3; i++) {
      digitalWrite(LED_BUILTIN, LOW);
      delay(170);
      digitalWrite(LED_BUILTIN, HIGH);
      delay(30);
    }
  }

  WiFi.begin(SSID, PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    digitalWrite(LED_BUILTIN, LOW);
    delay(100);
    digitalWrite(LED_BUILTIN, HIGH);
    delay(400);
  }

  if (WiFi.status() == WL_CONNECTED) {
    for (int i = 0; i < 5; i++) {
      digitalWrite(LED_BUILTIN, LOW);
      delay(30);
      digitalWrite(LED_BUILTIN, HIGH);
      delay(170);
    }
  }

  Serial.println();
  Serial.print("Connected! IP: ");
  Serial.println(WiFi.localIP());

  server.on("/", handleRoot);
  server.on("/color", handleColor);
  server.on("/reboot", handleReboot);
  server.begin();
  Serial.println("🌐 Web server started");
  Udp.begin(LOCAL_UDP_PORT);
}

void loop() {
  unsigned long now = millis();
  if (!connected) {
    if (now - lastUpdate >= BROADCAST_INTERVAL) {
      Udp.beginPacket(BROADCAST_IP, LOCAL_UDP_PORT);
      Udp.print(BROADCAST_MESSAGE_PREFIX);
      Udp.print(WiFi.localIP());
      Udp.print(BROADCAST_MESSAGE_SUFFIX);
      Udp.endPacket();
    }
  }
  server.handleClient();
  if (now - lastUpdate > stepDelay) {
    bool allEqual = true;
    for (int i = 0; i < WS2812B_COUNT; i++) {
      if (currentStrip[i] != targetStrip[i]) {
        allEqual = false;
        break;
      }
    }
    if (allEqual) return;

    if (currentStep >= steps) {
      currentStep = 0;
      currentBrightness = targetBrightness;
      startBrightness = currentBrightness;
      FastLED.setBrightness(currentBrightness);
      for (int i = 0; i < WS2812B_COUNT; i++) {
        currentStrip[i] = startStrip[i] = targetStrip[i];
      }
      FastLED.show();
      return;
    }

    lastUpdate = now;

    float t = (float)(currentStep) / steps;
    t = 3 * (float)pow(t, 2) - 2 * (float)pow(t, 3);  // ease-in-ease-out
    for (int i = 0; i < WS2812B_COUNT; i++) {
      uint8_t r = startStrip[i].r + (targetStrip[i].r - startStrip[i].r) * t;
      uint8_t g = startStrip[i].g + (targetStrip[i].g - startStrip[i].g) * t;
      uint8_t b = startStrip[i].b + (targetStrip[i].b - startStrip[i].b) * t;
      currentStrip[i] = CRGB(r, g, b);
    }
    currentBrightness =
        startBrightness + (targetBrightness - startBrightness) * t;
    FastLED.setBrightness(currentBrightness);
    FastLED.show();

    currentStep++;
  }
}
