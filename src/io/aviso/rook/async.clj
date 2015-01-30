(ns io.aviso.rook.async
  "Provides utilities for invoking and implementing asynchronous handlers and middleware.

  Async handlers operate much the same as normal Ring handlers: they are passed a Ring request map,
  and they compute and return a Ring response. The difference is that the handler is typically
  implemented as a `clojure.core.async/go` block, and the return value is a channel that recieves the Ring response
  map.

  Because nil cannot be put on a channel, handlers that would like to
  return nil should `close!` the associated channel.

  Async middleware comes in three categories.

  * Basic middleware, which simply adds or modifies the Ring request map,
    works the same as traditional synchronous middleware: it may modify the request map
    before passing it to its delegate handler; the return value will be a channel, rather than a map.

  * Intercepting middleware may perform an immediate (non-asynchronous) computation and return a value,
    or may proceed directly to the delegate asynchronous handler. The returned value must be wrapped
    in a channel (using [[result->channel]]). An example of this is middleware that performs authentication
    or input validation.

  * Complex middleware that operates on the return value from the delegated handler, or must
    perform its own async operations, is more challenging.
    The delegated handler should be invoked inside a [[safe-go]] block, so that the result from the handler
    can be obtained without blocking."
  (:require io.aviso.rook.internals
            [clojure.core.async :as async :refer [chan go >! <! <!! >!! thread put! take! close! alts!!]]
            [io.aviso.toolchest.exceptions :refer [to-message]]
            ring.middleware.params
            ring.middleware.keyword-params
            [ring.middleware
             [session :as session]
             [format-params :as format-params]
             [format-response :as format-response]]
            [io.aviso.rook
             [schema-validation :as sv]
             [response-validation :as rv]
             [utils :as utils]]
            [potemkin :as p])
  (:import (javax.servlet.http HttpServletResponse)))

(p/import-vars [io.aviso.rook.internals

                safety-first
                safe-go
                safe-thread
                async-handler->ring-handler
                result->channel
                ring-handler->async-handler])

(defn wrap-with-schema-validation
  "The asynchronous version of schema validation."
  [handler metadata]
  (sv/wrap-with-schema-validation handler metadata result->channel))

(defn wrap-restful-format
  "Asychronous version of `ring.middleware.format/wrap-restful-format`; this implementation
  will work properly inside an asynchronous pipeline."
  ([handler]
    (wrap-restful-format handler [:json-kw :edn]))
  ([handler formats]
    (let [req-handler (format-params/wrap-restful-params handler :formats formats)]
      (fn [request]
        (let [response-ch (chan 1)]
          ;; To keep things fully asynchronous, we first invoke the downstream handler.
          (take! (try
                   (req-handler request)
                   (catch Throwable t
                     (result->channel
                       (utils/failure-response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                                               "unexpected-exception"
                                               (to-message t)))))
                 (fn [handler-response]
                   (if handler-response
                     (put! response-ch
                           (->
                             ;; We can't pass the asynchronous req-handler to w-r-r, it expects
                             ;; a sync handler. Instead, we provide a "fake" sync handler
                             ;; that returns the previously obtained handler response.
                             (constantly handler-response)
                             (format-response/wrap-restful-response :formats formats)
                             ;; Capture and invoke the wrapped, fake handler
                             (as-> f (f request))))
                     (close! response-ch))))
          response-ch)))))

(defn wrap-with-standard-middleware
  "The equivalent of [[rook/wrap-with-standard-middleware]], but for an asynchronous pipeline."
  [handler]
  (-> handler
      wrap-restful-format
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(def ^:private session-options-alias
  (ns-resolve 'ring.middleware.session 'session-options))

(defn wrap-session
  "The async version of `ring.middleware.session/wrap-with-session`.
  Session handling is not part of the standard middleware, and must be
  added in explicitly."
  ([handler] (wrap-session handler {}))
  ([handler options]
    (let [options' (session-options-alias options)]
      (fn [request]
        (let [response-ch (chan 1)
              request' (session/session-request request options')]
          (take! (handler request')
                 (fn [handler-response]
                   (if handler-response
                     (put! response-ch (session/session-response handler-response request' options'))
                     (close! response-ch))))
          response-ch)))))

(defn wrap-with-response-validation
  "The async version of [[rv/wrap-with-response-validation]]."
  {:since "0.1.11"}
  ([handler metadata]
    (wrap-with-response-validation handler metadata true))
  ([handler metadata enabled]
    (if enabled
      (if-let [responses (:responses metadata)]
        (fn [request]
          (let [response-ch (chan 1)]
            (take! (handler request)
                   (fn [response]
                     (put! response-ch (rv/ensure-matching-response response (:function metadata) responses))))
            response-ch))))))

(defn timed-out?
  "Checks if timeout-ch (a channel returned from clojure.core.async/timeout) is closed.

  Remember to never close a timeout channel.

  This should not be applied to a non-timeout channel, will consume and discard a single value from the channel's
  buffer if there is one."
  {:added "0.1.24"}
  [timeout-ch]
  (let [sentinel-ch (doto (chan 1)
                      (>!! :sentinel))
        [_ ch] (alts!! [timeout-ch sentinel-ch] :priority true)]
    (identical? ch timeout-ch)))
