.PHONY: build test lint check install clean

build:
	./gradlew :app:assembleDebug

test:
	./gradlew :app:testDebugUnitTest

lint:
	./gradlew :app:lintDebug

check:
	./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

install:
	./gradlew :app:installDebug

clean:
	./gradlew clean
