NDK_BIN := $(HOME)/Library/Android/sdk/ndk/$(shell ls $(HOME)/Library/Android/sdk/ndk/ | sort -V | tail -1)/toolchains/llvm/prebuilt/darwin-x86_64/bin
CC      := $(NDK_BIN)/aarch64-linux-android21-clang
CFLAGS  := -O2 -Wall -Wextra -static
TARGET  := one35uinputd

.PHONY: all clean push

all: $(TARGET)

$(TARGET): one35uinputd.c cJSON.c
	$(CC) $(CFLAGS) -o $@ $^

clean:
	rm -f $(TARGET)

push: $(TARGET)
	adb push $(TARGET) /data/local/tmp/$(TARGET)
	adb shell chmod +x /data/local/tmp/$(TARGET)
