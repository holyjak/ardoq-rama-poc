# rama-clojure-starter

PoC of Ardoq based on Rama - Ardoq hackaton 2023.

**See `doc/` for detail.**

Note that Rama AOT compiles a specific Clojure version in its jar, so you can't specify a different version of Clojure in your dependencies.

# Notes

## Cursive

* Awesome
* Some macros lack docstring, e.g. <<sources, source>
* Currently, Rama has Clojure 1.11.1 built-in but Cursive believes `random-uuid` doesn't exist (though it works fine in the repl)

## Rama setup

* Run with lein profiles dev,provided
* Had to add nrepl dependency to for Cursive

## Rama learnings

* See my #rama questions https://clojurians.slack.com/archives/C05N2M7R6DB/p1702471642548209
* Topologies: finish with `local-transform` to write the data
* I can't use Clojure flow control constructs such as `or` in a dataflow > use `or>`, `<<if` etc. Q: What can I use??
  * println 
* Q: How to do compare-and-set ? Use `assert!` in the flow?
* How to throw an exception? A: `assert!` or `throw!` ? DON'T: "Any exception thrown anywhere in the processing of a depot record will cause that topology event to retry processing of that record"
* Tip: Use rama-helper's idgen (module-local ID sequence) for 64b IDs + a pstate mapping it to uuids - a long 1/2 the size of uuid
* Can't throw / assert in a flow - keeps retrying
* Implementing CAS