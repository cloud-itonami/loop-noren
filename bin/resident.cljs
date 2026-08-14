#!/usr/bin/env nbb
;; 常駐の 1 日ぶん。LaunchAgent（deploy/）から呼ばれる。
;;
;;   nbb bin/resident.cljs            # 何をするかだけ出す
;;   nbb bin/resident.cljs --apply    # 実際に回して、書いて、預ける
;;
;; ## なぜ 1 本のスクリプトか
;;
;; discover → tick → 記録 → 預ける（annex）→ push は**この順でしか正しくない**。
;; plist を 5 本に割ると順序が時刻の偶然になり、「まだ commit していない raw を
;; copy しようとする」が起きる。順序が要るものは 1 プロセスに入れる。
;;
;; ## 預けられなかったことを成功にしない
;;
;; LaunchAgent は Keychain を非対話で読めないことがある。creds が無ければ
;; **copy をスキップして、その旨をログに残す** —— git には raw の pointer だけが
;; 残り、実体はローカルにしか無い状態になる。これは「後で預ける必要がある」で
;; あって「預けた」ではないので、区別できる形で出す。
(ns loop-noren.resident
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def apply? (boolean (some #{"--apply"} *command-line-args*)))
(def root (path/resolve (path/dirname *file*) ".."))

(def classpath
  "west checkout の兄弟を辿る。**ここが正本**で、plist はこれを埋め込む。"
  (str/join ":" (map #(path/join root %)
                     ["src"
                      "../../kotoba-lang/noren/src"
                      "../../kotoba-lang/design-quality/src"
                      "../../kotoba-lang/jp-go-digital-design-system/src"
                      "../../kotoba-lang/html/src"
                      "../../kotoba-lang/css/src"
                      "../../kotoba-lang/org-openstreetmap-overpass/src"
                      "../../net-kotobase/commoncrawl-actor/src"])))

(defn- run
  "→ `{:ok? :out}`。**例外にしない** —— 1 段落ちても残りの段は意味があり、
  特に「回せなかったが預けることはできる」が普通に起きる。"
  [label argv]
  (println (str "▸ " label))
  (if-not apply?
    (do (println (str "   (dry-run) " (str/join " " argv))) {:ok? true :out ""})
    (try
      (let [out (.toString (.execFileSync cp (first argv) (clj->js (rest argv))
                                          #js {:cwd root :maxBuffer (* 64 1024 1024)
                                               :timeout (* 30 60 1000)}) "utf8")]
        (doseq [l (take-last 6 (remove str/blank? (str/split-lines out)))] (println "   " l))
        {:ok? true :out out})
      (catch :default e
        (let [out (str (some-> (.-stdout e) (.toString "utf8"))
                       (some-> (.-stderr e) (.toString "utf8")))]
          (doseq [l (take-last 4 (remove str/blank? (str/split-lines out)))] (println "   !" l))
          (println (str "   ! " label " が失敗した: " (.-message e)))
          {:ok? false :out out})))))

(defn- noren [& args]
  (run (str "noren " (str/join " " args))
       (concat ["nbb" "--classpath" classpath (path/join root "bin" "noren.cljs")] args)))

(defn- git [& args] (run (str "git " (str/join " " args)) (concat ["git"] args)))

(defn- require-ok! [label result]
  (when-not (:ok? result)
    (throw (ex-info (str label " failed; resident pass is incomplete") {}))))

(defn- sync-main! []
  ;; west leaves project checkouts detached. Resolve the remote ref explicitly;
  ;; `git push origin main` would otherwise push a stale local branch named
  ;; main rather than the checked-out HEAD.
  (require-ok! "fetch origin/main" (git "fetch" "origin" "main"))
  (require-ok! "fast-forward to origin/main" (git "merge" "--ff-only" "origin/main")))

(defn- publish-main! []
  ;; HEAD is a commit object in west's detached checkout, so Git cannot infer
  ;; that a not-yet-existing destination is a branch. Spell the full ref.
  (require-ok! "push resident branch"
               (git "push" "origin" "HEAD:refs/heads/resident/noren"))
  (let [merged (run "server-side merge resident/noren -> main"
                    ["gh" "api" "repos/cloud-itonami/loop-noren/merges"
                     "-f" "base=main" "-f" "head=resident/noren"
                     "-f" "commit_message=loop-noren: merge resident pass"])]
    (when (and (not (:ok? merged))
               (not (str/includes? (:out merged) "No commits between")))
      (require-ok! "server-side merge" merged)))
  (sync-main!))

(defn- dirty? []
  (try
    (-> (.execFileSync cp "git" #js ["status" "--porcelain"] #js {:cwd root})
        (.toString "utf8") str/trim seq boolean)
    (catch :default _ false)))

(defn- creds-present?
  "annex の S3 creds が使える状態か。**LaunchAgent から Keychain を非対話で
  開けると仮定しない**ので、見るのは 2 つだけ:

  - 環境変数（`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`）
  - `initremote` 時に git-annex がこの repo に保存した `.git/annex/creds/<uuid>`
    （実測 2026-08-11: `~/.config/git-annex/creds` ではなくこちらに入る）"
  [] (or (and (some-> js/process.env.AWS_ACCESS_KEY_ID not-empty)
              (some-> js/process.env.AWS_SECRET_ACCESS_KEY not-empty))
         (let [d (path/join root ".git" "annex" "creds")]
           (and (fs/existsSync d) (pos? (count (fs/readdirSync d)))))))

(println (str "loop-noren resident " (subs (.toISOString (js/Date.)) 0 19)
              (when-not apply? "  [dry-run]")))

(sync-main!)

;; 1) 発見 —— 名簿に加えるところまで。接触はしない
(noren "discover" "--accept")

;; 2) 1 周 —— 提案は governor を通ったものだけ、承認キュー行きは別（下記）
(noren "tick")

;; 3) 記録を git に落とす。**receipt と journal が先、raw の pointer も同じ commit**
;;
;; `git add` は data / journal / raw / .gitattributes だけに限る。共有 checkout
;; なので、別セッションが src/ を編集している最中に常駐が回ることがある ——
;; `git add -A` にすると他人の WIP を勝手に commit する（CLAUDE.md「並行エージェント
;; 運用」が禁じている形）。常駐が触ってよいのは自分が書いた面だけ。
(when (dirty?)
  (git "add" "data" "journal" "raw" ".gitattributes")
  (git "-c" "user.name=loop-noren" "-c" "user.email=noren@itonami.cloud"
       "commit" "-q" "-m" (str "loop-noren: resident pass " (subs (.toISOString (js/Date.)) 0 10))))

;; 4) 実体を object plane へ預ける
(if (creds-present?)
  (do (git "annex" "copy" "raw/" "--to" "kotobase" "--jobs" "1")
      (noren "verify-corpus"))
  (println "▸ annex copy をスキップ（S3 creds が無い）。raw の実体はローカルにのみ在る"))

;; 5) 上流へ
(publish-main!)

(println "resident pass 終了")
