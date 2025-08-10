#pragma once

#include <Arduino.h>

// Network
#define SSID "moto g84 5G_8526"
#define PASSWORD "11111111"
#define LOCAL_IP IPAddress(10, 240, 214, 125)
#define GATEWAY IPAddress(192, 168, 43, 1)
#define SUBNET IPAddress(255, 255, 255, 0)

#define LOCAL_UDP_PORT 6969
#define BROADCAST_IP IPAddress(255, 255, 255, 255)
#define BROADCAST_MESSAGE_PREFIX "esp{"
#define BROADCAST_MESSAGE_SUFFIX "}"
#define BROADCAST_INTERVAL 1000

// LED strip
#define WS2812B_PIN 13
#define WS2812B_COUNT 14

// Arrangement
#define NON_DISPLAY_COUNT 8
extern const uint8_t arrangement[NON_DISPLAY_COUNT];  // LED arrangement for the
                                                      // strip; left to right

#define DISPLAY_COUNT 6
extern const uint8_t displays[DISPLAY_COUNT];  // Digital display backlight LEDs

// EEPROM
#define ADDR_BRIGHTNESS 0
#define ADDR_MODE 1
#define ADDR_COLOR1 2
#define ADDR_COLOR2 5
#define EEPROM_SIZE 8

// Macros
#ifndef min
#define min(a, b) ((a) < (b) ? (a) : (b))
#endif

#ifndef max
#define max(a, b) ((a) > (b) ? (a) : (b))
#endif
