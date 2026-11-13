// Host tool: checks a config.ini file with the firmware parser before the copy to the MicroSD card.
// Usage: check_config <config.ini>
// Exit codes: 0 no rejected line, 1 rejected lines, 2 missing file or file above 4096 bytes.
#include <stdio.h>

#include "../rlcd_panel/device_config.h"

using namespace rlcd_panel;

int main(int argc, char** argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: %s <config.ini>\n", argv[0]);
    return DeviceConfigCheck::EXIT_UNUSABLE;
  }
  FILE* f = fopen(argv[1], "rb");
  if (f == nullptr) {
    fprintf(stderr, "ERROR cannot read %s\n", argv[1]);
    return DeviceConfigCheck::EXIT_UNUSABLE;
  }
  // One byte more than the limit shows an oversized file without reading all of it.
  static char data[DeviceConfigParser::MAX_FILE_SIZE + 1];
  const size_t len = fread(data, 1, sizeof(data), f);
  const bool read_error = ferror(f) != 0;
  fclose(f);
  if (read_error) {
    fprintf(stderr, "ERROR cannot read %s\n", argv[1]);
    return DeviceConfigCheck::EXIT_UNUSABLE;
  }
  char report[DeviceConfigCheck::MAX_REPORT_SIZE];
  const int code = DeviceConfigCheck::run(data, len, report, sizeof(report));
  fputs(report, code == DeviceConfigCheck::EXIT_UNUSABLE ? stderr : stdout);
  return code;
}
