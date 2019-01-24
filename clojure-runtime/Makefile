.PHONY: all get_spec clean_spec

all: Clojure/Clojure.Source/clojure/spec Clojure/Clojure.Source/clojure/core/specs
	@CLOJURE_SPEC_SKIP_MACROS=true msbuild

get_spec: Clojure/Clojure.Source/clojure/spec Clojure/Clojure.Source/clojure/core/specs

clean_spec:
	@rm -fr Clojure/Clojure.Source/clojure/spec
	@rm -fr Clojure/Clojure.Source/clojure/core/specs

clean:
	@rm -fr bin tools

package: all
	@nuget pack package.nuspec

Clojure/Clojure.Source/clojure/spec:
	@ git submodule init
	@ git submodule update
	@ cd clr.spec.alpha && \
	echo "spec.alpha" `git rev-parse HEAD`
	@cp -r clr.spec.alpha/src/main/clojure/clojure/spec Clojure/Clojure.Source/clojure/spec

Clojure/Clojure.Source/clojure/core/specs:
	@ git submodule init
	@ git submodule update
	@ cd clr.core.specs.alpha && \
	echo "core.specs.alpha" `git rev-parse HEAD`
	@cp -r clr.core.specs.alpha/src/main/clojure/clojure/core/specs Clojure/Clojure.Source/clojure/core/specs
