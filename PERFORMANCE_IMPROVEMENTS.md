# Book Cycle 改善案

対象: `book-cycle/src/main/java/bookcycle/BookCycle.java`

この文書は、現在の安定性を維持したまま Book Cycle の1サイクル時間を短縮し、ガーディアンXP補給を含む長時間放置時の実効効率を上げるための改善案をまとめたものです。

## 0. 現状の前提

現在の実装は、単純な固定ディレイ式ではなく、かなり良い状態確認型になっています。

特に以下は維持するべきです。

- `MenuAction(ready, send, confirmed, finish, retry)` による操作管理
- クリック前の状態を保存し、サーバーから実際にスロット状態が変わるまで `confirmed` を成立させない方式
- `ContainerPredictionMixin` によって自動クリック時のクライアント側ローカル予測を止める方式
- `menu.getStateId()` を利用したサーバー同期確認
- エンチャント結果が `ENCHANTED_BOOK` になるまで次へ進まない確認
- 砥石結果が `BOOK` になるまで次へ進まない確認
- タイムアウト・リトライ・本の回収処理
- `canSwing()` の `getAttackStrengthScale(0) >= 1` 確認

速度改善のために、これらを削って固定時間だけで進める方式には戻さない方がよいです。

現在の実測では、ガーディアンを「2エンチャントにつき1回」攻撃すると、経験値レベルが概ね21～25で安定しています。このため、XP補給自体は現在の比率でほぼ釣り合っており、今後は待機時間と不要なGUI往復を削る方が効果が大きいです。

---

# 1. 最優先: ガーディアン攻撃後の固定待機を削る

現在の `tickGuardians()` では、攻撃成功後に以下を実行しています。

```java
client.gameMode.attack(player, aimedGuardian);
player.swing(...);

actionDelay = afterAttackDelayTicks;
completedAtLastSwing = completed;
lastSwingAt = System.nanoTime();
hasSwung = true;
```

`afterAttackDelayTicks` を20に設定している場合、2サイクルごとに約1秒停止します。

これは現在の構成では非常に大きなボトルネックです。

## 改善案

通常時は `afterAttackDelayTicks = 0` または1程度を基本とし、XPが実際に不足した場合だけ `WAIT_FOR_XP` に入るようにします。

現在すでに、

```java
if (player.hasInfiniteMaterials() || player.experienceLevel >= xpNeeded) {
    xpNeeded = 1;
    open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
}
```

という判定があるため、XPに余裕があるときまで固定で長く待つ必要はありません。

### 推奨

1. 20 tick
2. 5 tick
3. 2 tick
4. 1 tick
5. 0 tick

の順で実サーバー上の安定性を比較します。

確認する値:

- 最低経験値レベル
- 最大経験値レベル
- エンチャント失敗数
- XP不足による `WAIT_FOR_XP` 発生回数
- 1分あたりの完了サイクル数

現在21～25で安定しているなら、0～2tickでも成立する可能性があります。

---

# 2. 最優先: 2サイクルごとの「無駄なエンチャント台オープン」をなくす

現在の通常サイクルは概ね以下です。

```text
砥石で解除
↓
本をホットバーへ回収
↓
エンチャント台を開く
↓
loadBook()
↓
completed++
↓
aimIfDue()
↓
2サイクル経過していたらエンチャント台を閉じる
↓
ガーディアンを狙う
↓
攻撃
↓
再びエンチャント台を開く
```

つまり、ガーディアン攻撃が必要な回では、

**攻撃判定のためだけにエンチャント台を1回開いて、すぐ閉じています。**

2サイクルごとに1回発生するため、かなり大きな無駄です。

## 改善案

「サイクル完了」の確定を、次のエンチャント台を開いた後ではなく、**砥石から通常の本をサーバー確認済みで回収した時点**に移します。

理想:

```text
砥石結果を回収
↓
本がホットバーに戻ったことをconfirmed
↓
completed++
↓
2サイクル経過？
├─ YES → ガーディアン攻撃
│          ↓
│        エンチャント台を開く
└─ NO  → エンチャント台を開く
```

これにより、攻撃する回に発生していた

```text
エンチャント台を開く
↓
すぐ閉じる
```

を完全に削除できます。

## 実装イメージ

現在の `grind()` の完了処理:

```java
recoverBook(client, menu, GrindstoneMenu.RESULT_SLOT, () -> {
    recoveringBook = true;
    open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
});
```

を、概念的には次のような構造へ変更します。

```java
recoverBook(client, menu, GrindstoneMenu.RESULT_SLOT, () -> {
    completeCycle(client);
});
```

そして:

```java
private void completeCycle(Minecraft client) {
    completed++;
    recoveryAttempts = 0;

    closeMenu();

    if (guardianMode && aimIfDue(client)) {
        return;
    }

    open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
}
```

ただし、現在の `recoveringBook` はタイムアウト復旧経路でも使用されているため、単純削除ではなく、

- 正常な砥石完了
- タイムアウト後の復旧
- 二重カウント防止

を分けて整理する必要があります。

重要なのは、**サイクル完了の基準を「次のエンチャント台が開いた」ではなく「砥石解除済みの本がホットバーへ戻った」へ移すこと**です。

---

# 3. ガーディアン攻撃を固定回数だけでなくXPレベルでも調整する

現在:

```java
private static final int CYCLES_PER_SWING = 2;
```

で、2サイクルごとに攻撃しています。

実測でLv21～25を維持できているため、この設定は現在かなり良いです。

ただしサイクルを高速化すると、2サイクルの経過時間も短くなります。

例:

- 0.50秒/サイクル → 2サイクル = 1.00秒
- 0.40秒/サイクル → 2サイクル = 0.80秒
- 0.30秒/サイクル → 2サイクル = 0.60秒

剣のフルチャージ判定はすでに `canSwing()` が行っているため、攻撃速度自体は安全側ですが、XP供給量とのバランスが変化します。

## 改善案

固定2サイクルを最低条件として残しつつ、経験値レベルで攻撃をスキップできるようにします。

例:

```text
2サイクル経過
↓
Lvが十分高い（例: 25以上）
    → 今回は攻撃を見送る
Lvが通常帯（例: 21～24）
    → 攻撃
Lvが不足
    → 最優先で攻撃
```

これにより、不要なガーディアン攻撃とGUI中断を減らせる可能性があります。

ただし、現在の「2回ごと」で実際に21～25へ安定しているため、これは1・2の改善後に検証する項目です。

---

# 4. 待機は「固定時間」より「最低1tick + confirmed」を維持する

現在の状態確認方式は正しい方向です。

高速化する場合も、

```text
クリック
↓
最低1tick
↓
confirmed確認
├─ 成功 → 即次へ
└─ 未反映 → 次tickでもう一度確認
```

を基本にします。

1msなどの高頻度ポーリングは不要です。

Minecraftのインベントリ更新はクライアントスレッド上で反映されるため、毎client tickの確認で十分です。

また、別スレッドから `ScreenHandler` / `Slot` 相当の状態へ触る構成は避けます。

---

# 5. `OPEN_DELAY_TICKS = 1` は当面維持する

```java
private static final int OPEN_DELAY_TICKS = 1;
```

は、GUIを開くブロック操作を次tickへ持ち越すための安全マージンとして機能しています。

ここを0にするより先に、

- 攻撃後の20tick
- 攻撃前の不要なエンチャント台open/close
- 各Stepの実測

を改善する方が効果が大きいです。

`OPEN_DELAY_TICKS = 0` は最後の微調整候補にします。

---

# 6. 各Stepの所要tickを計測する

現在「約0.5秒/サイクル」という全体値は分かっていますが、どこに何tick使っているかが見えていません。

最適化前に計測を追加すると、Codexによる推測修正を減らせます。

## 計測対象

最低限:

- OPEN_TABLE開始
- エンチャント台の初期内容受信
- 本投入送信
- 本投入confirmed
- ラピス投入送信
- ラピス投入confirmed
- エンチャント送信
- ENCHANTED_BOOK confirmed
- 本回収送信
- 本回収confirmed
- OPEN_GRINDSTONE開始
- 砥石の初期内容受信
- 本投入送信
- BOOK結果出現
- 結果回収送信
- 結果回収confirmed
- ガーディアン照準開始
- ガーディアン攻撃
- 次のOPEN_TABLE
- 1サイクル完了

## 出力例

```text
[BookCycle perf]
OPEN_TABLE           1t
WAIT_TABLE_CONTENTS  1t
PUT_BOOK             1t
ENCHANT_CONFIRM      1t
TAKE_BOOK             1t
OPEN_GRINDSTONE      1t
GRIND_RESULT          1t
TAKE_RESULT           1t
TOTAL                 8t / 400ms
```

10～100サイクル分を集計して、

- avg
- min
- max
- p95

を出すとよいです。

---

# 7. 攻撃後待機は「XP変化待ち」ではなく、XP不足時だけ待つ

ガーディアンを殴った直後に必ずXP反映を待つ方式にすると、毎回の遅延になります。

現在は経験値レベルに余裕があるため、

```text
攻撃
↓
即次サイクル開始
↓
XPは並行して反映
```

を基本にした方が高速です。

次のエンチャント時点で必要レベルを下回った場合だけ `WAIT_FOR_XP` に入れば十分です。

つまり、

**「攻撃したから待つ」のではなく「XPが足りないから待つ」**

へ統一します。

---

# 8. `ENCHANT_RETRY_TICKS = 20` は通常速度には影響しないが、ログを取って再評価する

```java
private static final int ENCHANT_RETRY_TICKS = 20;
```

は正常時には発動しないため、通常のサイクル速度のボトルネックではありません。

ただし、サーバーがエンチャント結果を落とした場合に1秒待つため、長時間運用で頻発しているなら効率に影響します。

改善する場合は、先に実サーバーで

- エンチャント送信 → confirmed までのtick分布

を記録します。

p95が2～3tick程度なら、リトライを6～10tick程度へ短縮できる可能性があります。

ただし、早すぎる再送は二重処理リスクの検証が必要なので、ここは攻撃後待機より優先度を下げます。

---

# 9. `TIMEOUT_TICKS = 80` はそのままでよい

4秒相当のタイムアウトは、通常速度では使用されません。

これは異常時の安全装置なので、速度目的で縮める必要はありません。

---

# 10. `ContainerPredictionMixin` は削除しない

現在:

```java
@Mixin(AbstractContainerMenu.class)
abstract class ContainerPredictionMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void bookCycle$predictClick(...) {
        if (player instanceof LocalPlayer && BookCycle.isSendingClick()) {
            info.cancel();
        }
    }
}
```

により、自動操作時にクライアントがクリック結果を先読みしてローカル状態を書き換えるのを抑えています。

このため現在の `confirmed` は、クライアント予測ではなくサーバー由来の更新を見やすくなっています。

速度改善のためにこのMixinを外すと、一見速く見えても「サーバー反映前に次の処理へ進む」可能性が高くなるため、維持推奨です。

---

# 11. 改善後の理想フロー

現在より効率の良い正常系:

```text
[エンチャント台]
本を入れる
↓
confirmed
↓
ラピス確認/投入
↓
confirmed
↓
エンチャント
↓
ENCHANTED_BOOK confirmed
↓
本をホットバーへ回収
↓
confirmed

[砥石]
砥石を開く
↓
本を投入
↓
BOOK出現
↓
本をホットバーへ回収
↓
confirmed
↓
completed++

[攻撃判定]
2サイクル経過かつ攻撃が必要？
├─ NO
│   ↓
│ エンチャント台を開く
│
└─ YES
    ↓
  ガーディアンを攻撃
    ↓
  固定20tick待たない
    ↓
  エンチャント台を開く

次のエンチャント時にXP不足ならWAIT_FOR_XP
```

---

# 12. 優先順位

## P0: まず実施

1. 攻撃後20tick待機を0～2tickで検証
2. 砥石完了時点で `completed++` し、攻撃判定を次のエンチャント台open前へ移動
3. 各Stepの所要tickログを追加

この3つが最も効果が大きいです。

## P1: P0後に実施

4. 経験値レベルに応じた攻撃スキップ
5. エンチャントconfirmedの実測から `ENCHANT_RETRY_TICKS` を調整

## P2: 最後に検証

6. `OPEN_DELAY_TICKS` を1→0にできるか検証
7. tick未満のイベント駆動最適化

P2は複雑さに対して改善幅が小さい可能性が高いため、P0/P1より先に行わない方がよいです。

---

# 13. ベンチマーク方法

変更ごとに最低5～10分動かし、次を記録します。

| 指標 | 目的 |
|---|---|
| cycles/min | 実効速度 |
| avg cycle ticks | 平均速度 |
| p95 cycle ticks | ラグ時の安定性 |
| min/max XP level | XP供給バランス |
| guardian attacks/min | 攻撃頻度 |
| WAIT_FOR_XP count | XP不足頻度 |
| retry count | サーバー同期失敗頻度 |
| recovery count | 復旧頻度 |
| stop reason | 長時間運用上の問題 |

特に、

```text
afterAttackDelayTicks = 20 / 5 / 2 / 1 / 0
```

を同条件で比較します。

---

# 14. 目標

現在約0.5秒/サイクルなら、

- 0.40秒/サイクル = 約1.25倍
- 0.35秒/サイクル = 約1.43倍
- 0.30秒/サイクル = 約1.67倍

です。

最初の目標は **0.35～0.40秒を安定維持** に置くのがよいです。

0.30秒以下では、GUI同期よりも

- サーバー応答
- エンチャント台/砥石のGUI開閉
- 剣のフルチャージ
- XP反映

の方が支配的になる可能性が高くなります。

---

# 15. Codexへの実装方針

Codexには「単純に待ち時間を削る」ではなく、以下を明示するのがよいです。

1. 既存の `MenuAction.ready/send/confirmed/finish/retry` を維持する
2. `ContainerPredictionMixin` を維持する
3. 固定待機削減より、不要なGUI往復を先に消す
4. 砥石結果の回収confirmedをサイクル完了点にする
5. ガーディアン攻撃判定を次のエンチャント台open前に行う
6. 攻撃後はXPが不足していない限り即再開する
7. 変更前後でcycles/minとStep別tickを比較する
8. タイムアウト・復旧経路を壊さない
9. 二重 `completed++` を起こさない
10. 自動テストで「missing enchant response」「missing grindstone response」の復旧を引き続き通す

最適化の中心は、**サーバー確認を減らすことではなく、確認済みの状態遷移の間にある無駄な待機とGUI往復を減らすこと**です。
