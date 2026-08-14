# loop-noren

**飲食店・EC 事業者の公開面を測り、直すか建て替えるかを処方し、governor を
通ってから cloud-itonami の承認キューへ 1 件だけ提案する continuous
orchestrator。** 売るのは月額の面（`noren.plan`）で、売り場は
[itonami.cloud](https://itonami.cloud/) である。

判定は 1 つも持っていない —— 適格性も採点も処方も送信可否も
[`kotoba-lang/noren`](https://github.com/kotoba-lang/noren) にある。
`loop-*` は `:must-not [:own-domain-scoring-truth]`
（`manifest/repository-rules.edn`、ADR-2607299000）。

設計: superproject **ADR-2608111400**。

```
data/areas.edn        discover が歩く区画（宣言。導出しない）
data/sources.edn      prospect の出所（宣言。導出しない）
data/prospects.edn    見込み事業者（1 件ずつ人が宣言する）
data/suppression.edn  受信拒否（恒久。消さない）
data/corpus/<日>.edn  読み取り 1 回 = receipt 1 件。raw への {:path :sha256 :bytes}
journal/<日>.edn      決定事実。1 行 1 EDN、追記のみ
raw/<日>/<sha>-*.txt  読んだ本文・生返答・診断した HTML → git-annex → s3.kotobase.net
src/loop_noren/       registry(検査) / loop(順序) / letter(組み立て)
                      discover(発見) / sources(OSM・CC) / murakumo(LLM)
                      store + store_io + canonical(永続化)
bin/noren.cljs        外に出る唯一の場所。既定は dry-run
bin/resident.cljs     常駐の 1 日ぶん（deploy/ の LaunchAgent が呼ぶ）
```

## 永続化 —— canonical EDN は git、bytes は annex

`kouhou`（ADR-2608110200）と同型の 4 面。**役割が違うものを同じ場所に置かない。**

| 面 | 中身 | bytes |
|---|---|---|
| 名簿 `data/prospects.edn` | 見込み事業者の宣言 | git |
| journal `journal/<日>.edn` | 決定事実（追記のみ） | git |
| corpus receipt `data/corpus/<日>.edn` | 読み取り 1 回につき 1 件 | git |
| raw `raw/<日>/<sha>-*.txt` | 本文・生返答・診断した HTML | **annex → s3.kotobase.net** |

**receipt が 2 面の join。** 提案を見たら、それが**どの本文の**どの測定から出たかを
名指しでき、digest で照合できる。receipt が無ければ対応づけはファイル名頼りで、
それは内容について何も証明しない。

**journal に生返答を書かない。** 決定ログにモデルの出力が混ざると際限なく膨らむ。
journal が持つのは sha256 だけで、実体は raw 面に居る。

**raw を git に置かない。** 1 周で本文 20 件 + 生返答 20 件、毎日回せば年で数 GB。
actor を clone するのに corpus のダウンロードが要る状態にしない。

```bash
nbb --classpath "$CP" bin/noren.cljs verify-corpus   # identity（receipt どおりか）
git annex find --in kotobase raw/ | wc -l            # custody（object plane が持っているか）
git annex copy raw/ --to kotobase --jobs 1
datalad drop raw/                                    # 実体を落とす。pointer は残る
```

**この 2 つは別の問い。** `verify-corpus` は drop された bytes を `:absent` と数え
失敗にしない（drop できることが annex を使う理由）。custody は
`--in kotobase` の**行数**が答える —— `git annex fsck --from kotobase` の `ok` は
「照合するものが無かった」でも印字されるので custody の数として読まない
（kouhou の実測: 48 件中 10 件の欠落を見落とす）。

## 常駐（deploy/）

```bash
nbb deploy/install.cljs              # 何をするか
nbb deploy/install.cljs --apply      # LaunchAgent を入れる（毎日 09:20）
nbb deploy/install.cljs --remove --apply
```

installer は launchd の限定 PATH に Homebrew が含まれないことを考慮し、検出した
`nbb` の directory を `EnvironmentVariables/PATH` に固定する。これが無いと
`nbb` の `/usr/bin/env node` が解決できず、常駐は exit 127 になる。

1 日 1 回 `bin/resident.cljs` が discover --accept → tick → commit → annex copy →
verify → push を**この順で**回す。順序が要るものを plist に割らないのは、
時刻の偶然で「まだ commit していない raw を copy する」が起きるため。

**Worker ではなく常駐なのは、Common Crawl の range fetch も curl も git-annex も
isolate では動かないから。** cloud-itonami が edge に持つのは承認キューと cockpit で、
判断の実行は常駐側にある（cloud-murakumo の organism と同じ形）。常駐は latency と
費用を変えるだけで、**権限は変えない** —— 何を出してよいかは `noren.governor` が決める。

`git add` は `data` / `journal` / `raw` に限る。共有 checkout なので、
別セッションが `src/` を編集している最中に常駐が回ることがあり、`-A` にすると
他人の WIP を commit してしまう。

## 発見（discover）

```
OSM Overpass ── amenity=restaurant 等のタグ → 業種（表。推測しない）
             └─ website タグを持つ独立店だけ（チェーンは brand:wikidata で除外）
Common Crawl ── 本文。**まだ用があると決まっていない相手のサーバを叩かない**
             └─ CC に無いときだけ live を 1 回（status を見る。404 は本文にしない）
murakumo-main ─ 本文から**原文にそのまま在る文字列**だけを抽出
             └─ verify-extraction が 1 つずつ照合。verbatim で無いものは落とす
DiscoveryGovernor ─ 受理 or 却下。受理されたものだけが名簿に載る
```

モデルは `murakumo-main` alias で解決する（ADR-2607173100）。具体の model id を
どこにも焼かない —— 解決順は env override → alias → endpoint のみ。リクエストの形は
**endpoint の形**から決める（`/chat/completions` なら OpenAI 形、`/v1/messages` なら
Anthropic 形）。alias が指す先は alias の持ち物であって、こちらが決めることではない。

**モデルが答えないことを、相手についての判定にしない。** 応答が無ければ候補は
`:defer`（次の周へ）で、却下ではない。2 回続けて落ちたら以降は呼ばない
（circuit breaker）—— モデルが落ちているときに全件へ同じ待ちを払うのは、
1 件目で分かったことを 20 回確かめているだけである。

## 1 周

```
observe   ── prospect/eligible（業種 / 特定電子メール法 3 条 1 項 4 号 / 観測の鮮度）
evaluate  ── 公開面を取得 → diagnose（design-quality の UI 12 軸 + presence 8 軸）
decide    ── prescribe（測った軸だけの claim）→ letter → NorenGovernor
act       ── :commit なら承認キューへ effect（**送信ではない**）
record    ── journal に 1 行。skip も hold も理由つきで残る
```

**1 周の提案は 1 件**（`loop-noren.loop/max-per-tick`）。5 件積むとどれも
読まれず、queue を読まれなくすることが測定を全部無駄にする唯一の方法である
（`kaiyu-kaizen` と同じ理由）。

## 走らせる

```bash
CP="src:test:../../kotoba-lang/noren/src:../../kotoba-lang/design-quality/src:\
../../kotoba-lang/jp-go-digital-design-system/src:../../kotoba-lang/html/src:../../kotoba-lang/css/src:\
../../kotoba-lang/org-openstreetmap-overpass/src:../../kotoba-lang/kotobase-commoncrawl-actor/src"

nbb --classpath "$CP" run_tests.cljs                    # 18 tests / 59 assertions
nbb --classpath "$CP" bin/noren.cljs discover           # 発見（dry-run。--accept で名簿に追記）
nbb --classpath "$CP" bin/noren.cljs llm-probe          # murakumo-main の解決と疎通だけ
nbb --classpath "$CP" bin/noren.cljs tick               # dry-run（何も送らない）
nbb --classpath "$CP" bin/noren.cljs diagnose https://example.test/ --catalog
nbb --classpath "$CP" bin/noren.cljs preview example-maru
nbb --classpath "$CP" bin/noren.cljs build brief.edn --out site.html
nbb --classpath "$CP" bin/noren.cljs tick --submit      # 承認キューへ積む
```

`--submit` は `NOREN_INGRESS_KEY`（提案しかできない per-tenant の鍵）が要る。
`kaizen` ingress（`kaiyu` が使っている実在の経路）と同じ形 —— `202 proposed` /
`200 already-open`、承認は cockpit の人がやり、**この loop は approve の鍵を
持たない**。

⚠ **server 側はまだ無い。** 実測 2026-08-11: `POST /api/{org}/{repo}/effects` は
502 で、effect を外から作る公開経路は cloud-itonami に存在しない
（`effectsOnRequestPost` は approve/reject 専用）。client は最終 contract で
書いてあり、404/502 は「未提供」として journal に残る —— 提案できなかったことを
提案したことにしない。 送信者表示（`NOREN_SENDER_NAME` / `NOREN_SENDER_ADDRESS` /
`NOREN_OPT_OUT`）は環境変数で上書きできるが、既定でも欠けていない ——
欠けたまま送れる既定を置かない。

## この loop がやらないこと

- **passive DNS からの発見。** ドメインは取れるが、**事業者と業種の対応づけを
  誰も宣言していない**。OSM 経路がそこを解けたのは「人が現地で付けたタグ」という
  宣言が在ったからで、passive DNS にはそれが無い。無いまま繋ぐと、結局こちらが
  業種を推測することになる。
- **EC 事業者の発見。** OSM に載るのは実店舗なので、オンライン専業の EC は
  この経路に出てこない。`:manual` と `:owner-inbound` のまま。
- **送信。** 承認キューに積むところまで。
- **文面の創作。** claim を増やせるのは測定だけで、`letter` は並べるだけ。
  LLM に書かせるときも差し替えてよいのは `letter` だけで、新しい売り文句を
  足した瞬間に governor が `:claim-without-evidence` で止める。
- **健全なサイトへの接触。** 診断が `:healthy` なら `:nothing-to-sell` で
  hold する。全件に提案が出る loop は、測っていないのと同じ。
- **モデルへの判定の委譲。** LLM が返せるのは原文に在る文字列だけで、業種は
  OSM のタグ、接触可否は `noren.governor`、品質は `design-quality` が決める。
