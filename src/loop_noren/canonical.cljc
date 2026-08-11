(ns loop-noren.canonical
  "行区切りの **canonical EDN**。map は印字前にキー順に並べる。

  同じ値が同じバイト列になっていないと、(a) `git diff` が並び替えだけの行を
  変更として見せ、(b) 同じ記録が 2 通りに digest される。receipt が sha256 で
  join する設計なので、後者は致命的になる。

  `kouhou.canonical`（ADR-2608110200）と同じ規律。**再利用せず小さく持つ**のは、
  actor 間で正本を共有すると片方の都合でもう片方のバイト列が変わるため
  （digest の互換は依存で守るものではない）。"
  (:require [clojure.string :as str]))

(defn ^:private sorted-deep
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (sorted-deep v)])) x)
    (set? x) (into (sorted-set) (map sorted-deep) x)
    (vector? x) (mapv sorted-deep x)
    (seq? x) (mapv sorted-deep x)
    :else x))

(defn line
  "1 レコード → 1 行の canonical EDN（末尾改行つき）。"
  [m]
  (str (pr-str (sorted-deep m)) "\n"))

(defn lines
  "レコード列 → 追記用の文字列。"
  [ms]
  (str/join (map line ms)))
