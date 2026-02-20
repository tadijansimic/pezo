#include <Arduino.h>
#include <ArduinoJson.h>
#include <EEPROM.h>
#include <ESP8266WebServer.h>
#include <ESP8266WiFi.h>
#include <FastLED.h>
#include <WiFiUdp.h>

#include "config.h"

CRGB currentStrip[WS2812B_COUNT], targetStrip[WS2812B_COUNT], startStrip[WS2812B_COUNT];
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
unsigned long lastUpdate = 0;

// --- EEPROM ---
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

void fillTargetStrip() {
    for (int i = 0; i < DISPLAY_COUNT; i++) {
        uint8_t idx = displays[i];
        if (idx < WS2812B_COUNT) {
            startStrip[idx] = currentStrip[idx];
            targetStrip[idx] = color1;
        }
    }

    float t = 0.0f;
    switch (mode) {
    case 0:
        for (int i = 0; i < NON_DISPLAY_COUNT; i++) {
            uint8_t idx = arrangement[i];
            if (idx >= WS2812B_COUNT) continue;

            startStrip[idx] = currentStrip[idx];
            t = (float)i / (NON_DISPLAY_COUNT - 1);
            t = 3 * (float)pow(t, 2) - 2 * (float)pow(t, 3);
            targetStrip[idx] = CRGB(
                color1.r + (color2.r - color1.r) * t,
                color1.g + (color2.g - color1.g) * t,
                color1.b + (color2.b - color1.b) * t);
        }
        break;
    case 1:
        for (int i = 0; i < NON_DISPLAY_COUNT / 2 - 1; i++) {
            uint8_t idx1 = arrangement[i], idx2 = arrangement[NON_DISPLAY_COUNT - 1 - i];
            if (idx1 >= WS2812B_COUNT || idx2 >= WS2812B_COUNT) continue;

            startStrip[idx1] = currentStrip[idx1];
            startStrip[idx2] = currentStrip[idx2];
            t = (float)i / (NON_DISPLAY_COUNT / 2 - 2);
            t = 3 * (float)pow(t, 2) - 2 * (float)pow(t, 3);
            targetStrip[idx1] = targetStrip[idx2] = CRGB(
                color1.r + (color2.r - color1.r) * t,
                color1.g + (color2.g - color1.g) * t,
                color1.b + (color2.b - color1.b) * t);
        }

        targetStrip[arrangement[NON_DISPLAY_COUNT / 2 - 1]] = targetStrip[arrangement[NON_DISPLAY_COUNT / 2]] = color2;

        break;
    case 2:
        for (int i = 0; i < NON_DISPLAY_COUNT; i++) {
            uint8_t idx = arrangement[i];
            if (idx < WS2812B_COUNT) {
                startStrip[idx] = currentStrip[idx];
                targetStrip[idx] = CRGB(
                    (color1.r + color2.r) / 2,
                    (color1.g + color2.g) / 2,
                    (color1.b + color2.b) / 2);
            }
        }
        break;
    }
}

// --- WEB SERVER ---
void handleSend() {
    doc["mode"] = mode;
    doc["brightness"] = targetBrightness;

    JsonObject color1Obj = doc.createNestedObject("color1");
    color1Obj["r"] = color1.r;
    color1Obj["g"] = color1.g;
    color1Obj["b"] = color1.b;

    JsonObject color2Obj = doc.createNestedObject("color2");
    color2Obj["r"] = color2.r;
    color2Obj["g"] = color2.g;
    color2Obj["b"] = color2.b;

    String response;
    serializeJson(doc, response);
    server.send(200, "application/json", response);
}
void handleColor() {
    if (server.hasArg("plain")) {
        deserializeJson(doc, server.arg("plain"));
        mode = doc["mode"].as<uint8_t>();
        targetBrightness = doc["brightness"].as<uint8_t>();

        color1.r = constrain(doc["color1"]["r"].as<int>(), 0, 255);
        color1.g = constrain(doc["color1"]["g"].as<int>(), 0, 255);
        color1.b = constrain(doc["color1"]["b"].as<int>(), 0, 255);
        color2.r = constrain(doc["color2"]["r"].as<int>(), 0, 255);
        color2.g = constrain(doc["color2"]["g"].as<int>(), 0, 255);
        color2.b = constrain(doc["color2"]["b"].as<int>(), 0, 255);

        EEPROM.write(ADDR_MODE, mode);
        EEPROM.write(ADDR_BRIGHTNESS, targetBrightness);
        writeCRGB(ADDR_COLOR1, color1);
        writeCRGB(ADDR_COLOR2, color2);
        EEPROM.commit();

        startBrightness = currentBrightness;
        currentStep = 0;
        fillTargetStrip();
        server.send(200, "text/plain", "OK");
    }
}

void setup() {
    Serial.begin(115200);
    EEPROM.begin(32);

    targetBrightness = EEPROM.read(ADDR_BRIGHTNESS);
    mode = EEPROM.read(ADDR_MODE);
    color1 = readCRGB(ADDR_COLOR1);
    color2 = readCRGB(ADDR_COLOR2);

    FastLED.addLeds<WS2812B, WS2812B_PIN, GRB>(currentStrip, WS2812B_COUNT);

    fillTargetStrip();
    for (int i = 0; i < WS2812B_COUNT; i++) {
        currentStrip[i] = targetStrip[i];
        startStrip[i] = targetStrip[i];
    }
    FastLED.setBrightness(targetBrightness);
    FastLED.show();

    WiFi.mode(WIFI_STA);
    WiFi.begin(SSID, PASSWORD);

    server.on("/color", HTTP_POST, handleColor);
    server.on("/color", HTTP_GET, handleSend);
    server.on("/", []() { server.send(200, "text/plain", "Online"); });
    server.begin();
    Udp.begin(LOCAL_UDP_PORT);
}

void loop() {
    server.handleClient();
    unsigned long now = millis();

    if (now - lastUpdate > stepDelay) {
        bool allEqual = true;
        for (int i = 0; i < WS2812B_COUNT; i++) {
            if (currentStrip[i] != targetStrip[i]) {
                allEqual = false;
                break;
            }
        }

        if (allEqual && currentBrightness == targetBrightness) return;

        if (currentStep >= steps) {
            currentStep = 0;
            currentBrightness = targetBrightness;
            startBrightness = currentBrightness;
            FastLED.setBrightness(currentBrightness);
            for (int i = 0; i < WS2812B_COUNT; i++) {
                currentStrip[i] = targetStrip[i];
                startStrip[i] = targetStrip[i];
            }
            FastLED.show();
            return;
        }

        lastUpdate = now;

        float t = (float)(currentStep) / steps;
        t = 3 * (float)pow(t, 2) - 2 * (float)pow(t, 3);

        for (int i = 0; i < WS2812B_COUNT; i++) {
            uint8_t r = startStrip[i].r + (targetStrip[i].r - startStrip[i].r) * t;
            uint8_t g = startStrip[i].g + (targetStrip[i].g - startStrip[i].g) * t;
            uint8_t b = startStrip[i].b + (targetStrip[i].b - startStrip[i].b) * t;
            currentStrip[i] = CRGB(r, g, b);
        }

        currentBrightness = startBrightness + (targetBrightness - startBrightness) * t;
        FastLED.setBrightness(currentBrightness);
        FastLED.show();

        currentStep++;
    }
}