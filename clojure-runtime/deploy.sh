#!/bin/sh

if [ -d $@/Infrastructure ]; then
	cp bin/4.0/Release/Microsoft*.dll $@/Infrastructure
	cp bin/4.0/Release/Clojure.dll $@/Infrastructure
	cp bin/4.0/Release/*.clj.dll $@/Infrastructure

	rm -fr $@/Source/clojure
	cp -r bin/4.0/Release/clojure $@/Source/

	echo "deployed to $@"
else
	echo "$@ is not an Arcadia repository, aborting."
fi

