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
data/sources.edn      prospect の出所（宣言。導出しない）
data/prospects.edn    見込み事業者（1 件ずつ人が宣言する）
data/suppression.edn  受信拒否（恒久。消さない）
src/loop_noren/       registry(検査) / loop(順序) / letter(組み立て)
bin/noren.cljs        外に出る唯一の場所。既定は dry-run
journal/<UTC日>.edn   append-only の証跡。1 行 1 EDN
```

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
../../kotoba-lang/jp-go-digital-design-system/src:../../kotoba-lang/html/src:../../kotoba-lang/css/src"

nbb --classpath "$CP" run_tests.cljs                    # 9 tests / 27 assertions
nbb --classpath "$CP" bin/noren.cljs tick               # dry-run（何も送らない）
nbb --classpath "$CP" bin/noren.cljs diagnose https://example.test/ --catalog
nbb --classpath "$CP" bin/noren.cljs preview example-maru
nbb --classpath "$CP" bin/noren.cljs build brief.edn --out site.html
nbb --classpath "$CP" bin/noren.cljs tick --submit      # 承認キューへ積む
```

`--submit` は `ITONAMI_OPERATOR_TOKEN` が要る。積まれるのは
`:effect/requires-approval true` の effect で、**実際の送信は cloud-itonami の
承認を通る。** 送信者表示（`NOREN_SENDER_NAME` / `NOREN_SENDER_ADDRESS` /
`NOREN_OPT_OUT`）は環境変数で上書きできるが、既定でも欠けていない ——
欠けたまま送れる既定を置かない。

## この loop がやらないこと

- **発見の自動化。** `data/sources.edn` の `:common-crawl` / `:passive-dns` は
  `:enabled? false`。取得経路は既存 repo に在るが、**ホスト名から業種を推測した
  瞬間にこの loop は推測を根拠に売り始める**ので、ISIC を誰が宣言するかが
  決まるまで繋がない。
- **送信。** 承認キューに積むところまで。
- **文面の創作。** claim を増やせるのは測定だけで、`letter` は並べるだけ。
  LLM に書かせるときも差し替えてよいのは `letter` だけで、新しい売り文句を
  足した瞬間に governor が `:claim-without-evidence` で止める。
- **健全なサイトへの接触。** 診断が `:healthy` なら `:nothing-to-sell` で
  hold する。全件に提案が出る loop は、測っていないのと同じ。
