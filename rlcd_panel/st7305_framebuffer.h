#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include "board_pins.h"

namespace rlcd_panel {

// ST7305 400x300 1-bit monochrome framebuffer (15,000 bytes).
// The physical ST7305 RAM on Waveshare ESP32-S3-RLCD-4.2 uses column-major
// memory layout with inverted Y. Each byte packs a 2-column x 4-row pixel block:
//   Column stride = HEIGHT / 4 = 75 blocks per column pair
//   byte_index = (x / 2) * 75 + ((299 - y) / 4)
//   Within each byte:
//     inv_y = 299 - y
//     local_y = inv_y % 4, local_x = x % 2
//     bit_pos = 7 - ((local_y * 2) + local_x)
class St7305Framebuffer {
public:
  static constexpr uint16_t WIDTH = RLCD_WIDTH;
  static constexpr uint16_t HEIGHT = RLCD_HEIGHT;
  static constexpr uint32_t BYTE_COUNT = RLCD_BUFFER_SIZE;
  static constexpr uint16_t BLOCKS_PER_COL = HEIGHT / 4; // 75

  St7305Framebuffer() {
    clear(false);
  }

  void clear(bool black = false) {
    memset(buffer_, black ? 0xFF : 0x00, sizeof(buffer_));
  }

  static bool computeByteAndMask(
      uint16_t x,
      uint16_t y,
      uint32_t& out_byte_index,
      uint8_t& out_bit_mask) {
    if (x >= WIDTH || y >= HEIGHT) {
      return false;
    }
    const uint16_t inv_y = static_cast<uint16_t>((HEIGHT - 1) - y);
    const uint16_t block_y = inv_y >> 2;
    const uint8_t local_y = static_cast<uint8_t>(inv_y & 3);
    const uint16_t byte_x = x >> 1;
    const uint8_t local_x = static_cast<uint8_t>(x & 1);

    out_byte_index = static_cast<uint32_t>(byte_x) * BLOCKS_PER_COL + block_y;
    const uint8_t bit_pos = static_cast<uint8_t>(7 - ((local_y << 1) | local_x));
    out_bit_mask = static_cast<uint8_t>(1U << bit_pos);
    return true;
  }

  void setPixel(uint16_t x, uint16_t y, bool black = true) {
    uint32_t byte_index = 0;
    uint8_t bit_mask = 0;
    if (!computeByteAndMask(x, y, byte_index, bit_mask)) {
      return;
    }
    if (black) {
      buffer_[byte_index] |= bit_mask;
    } else {
      buffer_[byte_index] &= static_cast<uint8_t>(~bit_mask);
    }
  }

  bool getPixel(uint16_t x, uint16_t y) const {
    uint32_t byte_index = 0;
    uint8_t bit_mask = 0;
    if (!computeByteAndMask(x, y, byte_index, bit_mask)) {
      return false;
    }
    return (buffer_[byte_index] & bit_mask) != 0;
  }

  void drawHLine(uint16_t x, uint16_t y, uint16_t w, bool black = true) {
    if (y >= HEIGHT || x >= WIDTH) {
      return;
    }
    for (uint32_t i = 0; i < w && (x + i) < WIDTH; ++i) {
      setPixel(static_cast<uint16_t>(x + i), y, black);
    }
  }

  void drawVLine(uint16_t x, uint16_t y, uint16_t h, bool black = true) {
    if (x >= WIDTH || y >= HEIGHT) {
      return;
    }
    for (uint32_t i = 0; i < h && (y + i) < HEIGHT; ++i) {
      setPixel(x, static_cast<uint16_t>(y + i), black);
    }
  }

  void drawRect(uint16_t x, uint16_t y, uint16_t w, uint16_t h, bool black = true) {
    if (w == 0 || h == 0 || x >= WIDTH || y >= HEIGHT) {
      return;
    }
    drawHLine(x, y, w, black);
    const uint32_t bottom_y = static_cast<uint32_t>(y) + h - 1;
    if (bottom_y < HEIGHT) {
      drawHLine(x, static_cast<uint16_t>(bottom_y), w, black);
    }
    drawVLine(x, y, h, black);
    const uint32_t right_x = static_cast<uint32_t>(x) + w - 1;
    if (right_x < WIDTH) {
      drawVLine(static_cast<uint16_t>(right_x), y, h, black);
    }
  }

  void fillRect(uint16_t x, uint16_t y, uint16_t w, uint16_t h, bool black = true) {
    if (w == 0 || h == 0 || x >= WIDTH || y >= HEIGHT) {
      return;
    }
    for (uint32_t dy = 0; dy < h && (y + dy) < HEIGHT; ++dy) {
      drawHLine(x, static_cast<uint16_t>(y + dy), w, black);
    }
  }

  // Minimal 5x7 ASCII glyph renderer (supports digits, uppercase, basic symbols)
  void drawChar(uint16_t x, uint16_t y, char c, uint8_t scale = 1, bool black = true) {
    if (scale == 0 || x >= WIDTH || y >= HEIGHT) {
      return;
    }
    const uint8_t* glyph = glyphForChar(c);
    for (uint32_t col = 0; col < 5; ++col) {
      const uint8_t bits = glyph[col];
      for (uint32_t row = 0; row < 7; ++row) {
        if ((bits >> row) & 0x01) {
          const uint32_t px = static_cast<uint32_t>(x) + col * scale;
          const uint32_t py = static_cast<uint32_t>(y) + row * scale;
          if (px < WIDTH && py < HEIGHT) {
            fillRect(static_cast<uint16_t>(px), static_cast<uint16_t>(py), scale, scale, black);
          }
        }
      }
    }
  }

  void drawText(uint16_t x, uint16_t y, const char* text, uint8_t scale = 1, bool black = true) {
    if (text == nullptr || scale == 0 || x >= WIDTH || y >= HEIGHT) {
      return;
    }
    uint32_t cursor_x = x;
    while (*text != '\0' && cursor_x < WIDTH) {
      drawChar(static_cast<uint16_t>(cursor_x), y, *text, scale, black);
      cursor_x += 6U * scale;
      ++text;
    }
  }

  const uint8_t* data() const {
    return buffer_;
  }

  uint8_t* mutableData() {
    return buffer_;
  }

private:
  uint8_t buffer_[BYTE_COUNT];

  static const uint8_t* glyphForChar(char c) {
    if (c >= 'a' && c <= 'z') {
      c = static_cast<char>(c - ('a' - 'A'));
    }
    switch (c) {
      case '0': { static const uint8_t g[5] = {0x3E, 0x51, 0x49, 0x45, 0x3E}; return g; }
      case '1': { static const uint8_t g[5] = {0x00, 0x42, 0x7F, 0x40, 0x00}; return g; }
      case '2': { static const uint8_t g[5] = {0x42, 0x61, 0x51, 0x49, 0x46}; return g; }
      case '3': { static const uint8_t g[5] = {0x21, 0x41, 0x45, 0x4B, 0x31}; return g; }
      case '4': { static const uint8_t g[5] = {0x18, 0x14, 0x12, 0x7F, 0x10}; return g; }
      case '5': { static const uint8_t g[5] = {0x27, 0x45, 0x45, 0x45, 0x39}; return g; }
      case '6': { static const uint8_t g[5] = {0x3C, 0x4A, 0x49, 0x49, 0x30}; return g; }
      case '7': { static const uint8_t g[5] = {0x01, 0x71, 0x09, 0x05, 0x03}; return g; }
      case '8': { static const uint8_t g[5] = {0x36, 0x49, 0x49, 0x49, 0x36}; return g; }
      case '9': { static const uint8_t g[5] = {0x06, 0x49, 0x49, 0x29, 0x1E}; return g; }
      case 'A': { static const uint8_t g[5] = {0x7E, 0x11, 0x11, 0x11, 0x7E}; return g; }
      case 'B': { static const uint8_t g[5] = {0x7F, 0x49, 0x49, 0x49, 0x36}; return g; }
      case 'C': { static const uint8_t g[5] = {0x3E, 0x41, 0x41, 0x41, 0x22}; return g; }
      case 'D': { static const uint8_t g[5] = {0x7F, 0x41, 0x41, 0x22, 0x1C}; return g; }
      case 'E': { static const uint8_t g[5] = {0x7F, 0x49, 0x49, 0x49, 0x41}; return g; }
      case 'F': { static const uint8_t g[5] = {0x7F, 0x09, 0x09, 0x09, 0x01}; return g; }
      case 'G': { static const uint8_t g[5] = {0x3E, 0x41, 0x49, 0x49, 0x7A}; return g; }
      case 'H': { static const uint8_t g[5] = {0x7F, 0x08, 0x08, 0x08, 0x7F}; return g; }
      case 'I': { static const uint8_t g[5] = {0x00, 0x41, 0x7F, 0x41, 0x00}; return g; }
      case 'J': { static const uint8_t g[5] = {0x20, 0x40, 0x41, 0x3F, 0x01}; return g; }
      case 'K': { static const uint8_t g[5] = {0x7F, 0x08, 0x14, 0x22, 0x41}; return g; }
      case 'L': { static const uint8_t g[5] = {0x7F, 0x40, 0x40, 0x40, 0x40}; return g; }
      case 'M': { static const uint8_t g[5] = {0x7F, 0x02, 0x0C, 0x02, 0x7F}; return g; }
      case 'N': { static const uint8_t g[5] = {0x7F, 0x04, 0x08, 0x10, 0x7F}; return g; }
      case 'O': { static const uint8_t g[5] = {0x3E, 0x41, 0x41, 0x41, 0x3E}; return g; }
      case 'P': { static const uint8_t g[5] = {0x7F, 0x09, 0x09, 0x09, 0x06}; return g; }
      case 'Q': { static const uint8_t g[5] = {0x3E, 0x41, 0x51, 0x21, 0x5E}; return g; }
      case 'R': { static const uint8_t g[5] = {0x7F, 0x09, 0x19, 0x29, 0x46}; return g; }
      case 'S': { static const uint8_t g[5] = {0x46, 0x49, 0x49, 0x49, 0x31}; return g; }
      case 'T': { static const uint8_t g[5] = {0x01, 0x01, 0x7F, 0x01, 0x01}; return g; }
      case 'U': { static const uint8_t g[5] = {0x3F, 0x40, 0x40, 0x40, 0x3F}; return g; }
      case 'V': { static const uint8_t g[5] = {0x1F, 0x20, 0x40, 0x20, 0x1F}; return g; }
      case 'W': { static const uint8_t g[5] = {0x3F, 0x40, 0x38, 0x40, 0x3F}; return g; }
      case 'X': { static const uint8_t g[5] = {0x63, 0x14, 0x08, 0x14, 0x63}; return g; }
      case 'Y': { static const uint8_t g[5] = {0x07, 0x08, 0x70, 0x08, 0x07}; return g; }
      case 'Z': { static const uint8_t g[5] = {0x61, 0x51, 0x49, 0x45, 0x43}; return g; }
      case '!': { static const uint8_t g[5] = {0x00, 0x00, 0x5F, 0x00, 0x00}; return g; }
      case '-': { static const uint8_t g[5] = {0x08, 0x08, 0x08, 0x08, 0x08}; return g; }
      case '.': { static const uint8_t g[5] = {0x00, 0x60, 0x60, 0x00, 0x00}; return g; }
      case ':': { static const uint8_t g[5] = {0x00, 0x36, 0x36, 0x00, 0x00}; return g; }
      case '~': { static const uint8_t g[5] = {0x04, 0x02, 0x04, 0x08, 0x04}; return g; }
      case '(': { static const uint8_t g[5] = {0x00, 0x1C, 0x22, 0x41, 0x00}; return g; }
      case ')': { static const uint8_t g[5] = {0x00, 0x41, 0x22, 0x1C, 0x00}; return g; }
      case '/': { static const uint8_t g[5] = {0x40, 0x30, 0x08, 0x06, 0x01}; return g; }
      case '%': { static const uint8_t g[5] = {0x23, 0x13, 0x08, 0x64, 0x62}; return g; }
      default:  { static const uint8_t g[5] = {0x00, 0x00, 0x00, 0x00, 0x00}; return g; }
    }
  }
};

} // namespace rlcd_panel
