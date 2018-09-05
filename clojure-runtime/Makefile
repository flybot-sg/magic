.PHONY: all get_spec clean_spec

all: Clojure/Clojure.Source/clojure/spec Clojure/Clojure.Source/clojure/core/specs
	@CLOJURE_SPEC_SKIP_MACROS=true xbuild
	@rm -fr Clojure/Clojure.Source/clojure/spec
	@rm -fr Clojure/Clojure.Source/clojure/core/specs

get_spec: Clojure/Clojure.Source/clojure/spec Clojure/Clojure.Source/clojure/core/specs
	
clean_spec:
	@rm -fr Clojure/Clojure.Source/clojure/spec
	@rm -fr Clojure/Clojure.Source/clojure/core/specs

clean: clean_spec
	@rm -fr bin

Clojure/Clojure.Source/clojure/spec:
	@mkdir -p .temp
	@cd .temp && \
	git clone https://github.com/arcadia-unity/clr.spec.alpha.git && \
	cd clr.spec.alpha && \
	echo `git rev-parse HEAD`
	@cp -r .temp/clr.spec.alpha/src/main/clojure/clojure/spec Clojure/Clojure.Source/clojure/spec
	@rm -rf .temp

Clojure/Clojure.Source/clojure/core/specs:
	@mkdir -p .temp
	@cd .temp && \
	git clone https://github.com/arcadia-unity/clr.core.specs.alpha.git && \
	cd clr.core.specs.alpha && \
	echo `git rev-parse HEAD`
	@cp -r .temp/clr.core.specs.alpha/src/main/clojure/clojure/core/specs Clojure/Clojure.Source/clojure/core/specs
	@rm -rf .temp