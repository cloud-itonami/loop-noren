#!/usr/bin/env nbb
;; 常駐の LaunchAgent を入れる。
;;
;;   nbb deploy/install.cljs            # 何をするか
;;   nbb deploy/install.cljs --apply    # plist を書いて bootstrap する
;;   nbb deploy/install.cljs --remove   # 外す
;;
;; nbb であって sh ではない（CLAUDE.md は新規 .sh を禁じ、ADR-2607173000 が
;; bb を script host から退役させている）。
;;
;; ## なぜ LaunchAgent で、Worker ではないか
;;
;; この loop は **Common Crawl の WARC を range fetch し、curl でページを取り、
;; git-annex に預ける**。どれも Worker の isolate では動かない。cloud-itonami が
;; edge に持つのは承認キューと cockpit で、**判断の実行は常駐側**にある
;; （cloud-murakumo の organism と同じ形）。常駐は latency と費用を変えるだけで、
;; 権限は変えない —— 何を出してよいかは `noren.governor` が決める。
(ns loop-noren.install
  (:require [clojure.string :as str]
            ["fs" :as fs] ["path" :as path] ["os" :as os] ["child_process" :as cp]))

(def apply? (boolean (some #{"--apply"} *command-line-args*)))
(def remove? (boolean (some #{"--remove"} *command-line-args*)))
(def label "cloud.itonami.noren.resident")
(def repo (path/resolve (path/dirname *file*) ".."))
(def home (os/homedir))
(def agents-dir (path/join home "Library" "LaunchAgents"))
(def plist-path (path/join agents-dir (str label ".plist")))

(defn- which [bin]
  (try (str/trim (.toString (.execSync cp (str "command -v " bin)) "utf8"))
       (catch :default _ nil)))

(defn- sibling-check
  "classpath は ../../kotoba-lang/{noren,design-quality,…} に届く。checkout が
  無ければ agent は毎日静かに落ち、log にしか理由が出ない。**今拒否する。**"
  []
  (let [needed ["noren" "design-quality" "jp-go-digital-design-system" "html" "css"
                "org-openstreetmap-overpass" "kotobase-commoncrawl-actor"]
        missing (remove #(fs/existsSync (path/join repo ".." ".." "kotoba-lang" % "src")) needed)]
    (when (seq missing)
      (throw (ex-info (str "west checkout が足りない: " (str/join ", " missing)
                           " — `west update --fetch smart` してから入れる")
                      {:missing missing})))))

(when remove?
  (when apply?
    (try (.execFileSync cp "launchctl" #js ["bootout" (str "gui/" (.-uid (os/userInfo)) "/" label)])
         (catch :default _ nil))
    (when (fs/existsSync plist-path) (fs/unlinkSync plist-path)))
  (println (str (if apply? "外した" "(dry-run) 外す") ": " plist-path))
  (js/process.exit 0))

(sibling-check)
(def nbb-path (or (which "nbb") (throw (ex-info "nbb が PATH に無い" {}))))

(def rendered
  (-> (.readFileSync fs (path/join repo "deploy" (str label ".plist.template")) "utf8")
      (str/replace "@REPO@" repo)
      (str/replace "@NBB@" nbb-path)
      (str/replace "@HOME@" home)))

(println (str (if apply? "書く" "(dry-run) 書く") ": " plist-path))
(println (str "  nbb  : " nbb-path))
(println (str "  repo : " repo))
(println "  実行 : 毎日 09:20（discover --accept → tick → commit → annex copy → push）")

(when apply?
  (fs/mkdirSync agents-dir #js {:recursive true})
  (fs/mkdirSync (path/join home ".gftd") #js {:recursive true})
  (fs/writeFileSync plist-path rendered)
  (let [uid (.-uid (os/userInfo))]
    ;; 冪等: 既に居たら外してから入れ直す。
    (try (.execFileSync cp "launchctl" #js ["bootout" (str "gui/" uid "/" label)])
         (catch :default _ nil))
    (.execFileSync cp "launchctl" #js ["bootstrap" (str "gui/" uid) plist-path])
    (println (str "bootstrap 済み: gui/" uid "/" label))
    (println "止めるとき: nbb deploy/install.cljs --remove --apply")))
