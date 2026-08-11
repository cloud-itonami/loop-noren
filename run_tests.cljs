#!/usr/bin/env nbb
;; nbb run_tests.cljs — loop-noren のテスト
(require '[clojure.test :as t] 'loop-noren.loop-test 'loop-noren.discover-test 'loop-noren.store-test)
(let [{:keys [fail error]} (t/run-tests 'loop-noren.loop-test 'loop-noren.discover-test 'loop-noren.store-test)]
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
