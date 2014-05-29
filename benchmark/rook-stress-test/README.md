# rook-stress-test

A simple utility for generating Rook dispatch tables with many
endpoints that can then be used for dispatch benchmarking.

## Usage

Spin up RST handlers from the REPL:

    $ lein repl
    > (require '[rook-stress-test.core :as rst])
    > (rst/start)

Then use `ab` to benchmark:

    $ ab -n10000 -c4 -k http://localhost:6001/foo/123
    $ ab -n10000 -c4 -k http://localhost:6002/foo/123

6001 uses the pattern matching dispatcher, 6002 uses the map traversal
dispatcher.

## License

Copyright © 2014 Aviso

Distributed under the terms of the
[Apache Software License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).
