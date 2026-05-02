DAEMON_BIN := daemon/one35uinputd
MODULE_BIN := module/one35uinputd
MODULE_ZIP := one35uinputd.zip

.PHONY: all daemon module install clean

all: daemon module

daemon:
	$(MAKE) -C daemon

module: daemon
	cp $(DAEMON_BIN) $(MODULE_BIN)
	cd module && zip -r ../$(MODULE_ZIP) . --exclude '*.DS_Store'

install: module
	adb push $(MODULE_ZIP) /data/local/tmp/$(MODULE_ZIP)
	@echo "Flash via: adb shell su -c 'magisk --install-module /data/local/tmp/$(MODULE_ZIP)'"

clean:
	$(MAKE) -C daemon clean
	rm -f $(MODULE_BIN) $(MODULE_ZIP)
