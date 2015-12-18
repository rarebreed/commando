# commando

A clojure library that handles the execution of child processes.

It abstracts the notion of launching a "process" (which could be a new thread, an SSH call, or some
other RPC mechanism) and being able to control and monitor the process in real-time.

Currently, it exposes local subprocess functionality via java's regular ProcessBuilder and Process
classes.  It does not (currently) attempt to do some of the fancy CLI handling that clj-commons-exec
does.  It does however allow for asynchronous calls (the launching thread does not need to block
while waiting for the subprocess to finish).

Indeed, the whole point of commando is to launch a subprocess and be able to monitor the output or
error streams in (semi) real time.  This allows for a broad range of use cases, such as monitoring
logs, or extracting information from the output, or handling events that the subprocess might throw.

SSH support is also available from the awesome clj-ssh library.  Commando treats executing a subprocess
locally or remotely the same through it's Executor defprotocl.

In the future, other process mechanisms will be supported, such as the pheidippides messaging bus.

## Usage

FIXME:  Show some examples of how to use commando

```
    => (require '[commando.command :as cmdr :refer [launch]])

    ;; Run in blocking mode
    => (def result (launch "ls -al"))
    => (clojure.pprint/pprint result)  ;; :status is exit value, and :output is the output

    ;; Run in non-blocking mode (install sysstat for this)
    => (def result (launch "iostat 2 5" :block? false))
    => result   ;; the REPL will be be free immediately since result is now a future

    ;; Throw exception on a failure
    => (def result (launch "ls /foo" :throws? true))

    ;; Call a subprocess remotely (Make sure you have copied your public key to the remote
    ;; host, and have ssh-add the identity before running
    (def result (launch "python -V" :host "myhost.nowhere.com"))

    ;; Set a different working directory
    (def result (launch "ls -al" :work-dir "/tmp"))



    ;; Consider a call a success only if something in the output is seen

    ;; DataTaps:  Are consumers of data.  they can have a handler which takes a message as an
    ;; argument, and do something with the message.  For example, you can write a handler to
    ;; watch for a traceback


```

## TODO

- More Windows testing
- Be able to send input to a process
- watch handler functionality
  - Use the LogProducer and LogConsumer for handling streams of output/error
  - Some processes might wrap a ref/agent/atom and supply a watch function
- result handler functionality
  - throw on failures
  - create a default handler (assume exit status of 0 is pass)
- Unit tests

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
