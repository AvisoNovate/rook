# rook-stress-test

A simple utility for generating Rook dispatch tables with many
endpoints that can then be used for dispatch benchmarking.

## Usage

    $ lein repl
    > (require '[rook-stress-test.core :as rst])
    > (rst/start)

## License

Copyright © 2014 Aviso

Distributed under the terms of the
[Apache Software License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).
