KEYSTORE := $(HOME)/.android/mofy.jks
KEY_ALIAS := mofy

.PHONY: keygen release install build-install

keygen:
	@if [ -f "$(KEYSTORE)" ]; then \
		echo "Keystore already exists at $(KEYSTORE)"; \
	else \
		keytool -genkey -v \
			-keystore "$(KEYSTORE)" \
			-keyalg RSA -keysize 2048 -validity 10000 \
			-alias "$(KEY_ALIAS)"; \
		echo ""; \
		echo "Add to .env.prod:"; \
		echo "  KEYSTORE_PATH=$(KEYSTORE)"; \
		echo "  KEYSTORE_PASSWORD=<password you entered>"; \
		echo "  KEY_ALIAS=$(KEY_ALIAS)"; \
		echo "  KEY_PASSWORD=<password you entered>"; \
	fi

release:
	cd android && ./gradlew :app:assembleRelease -q

install:
	adb install -r android/app/build/outputs/apk/release/app-release.apk

build-install: release install

# Tag the current commit for F-Droid's UpdateCheckMode: Tags
# Usage: make tag VERSION=0.2
tag:
	git tag -a "v$(VERSION)" -m "Release v$(VERSION)"
	git push origin "v$(VERSION)"
