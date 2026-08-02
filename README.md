# Rain's TPA

Teleport requests that ask first. Nine commands, a menu that can do all nine, and `/back`.

```
/tpa [player]        ask to teleport to them; with nobody named, opens the menu
/tpahere <player>    ask them to teleport to you
/tpaccept [player]   say yes
/tpdeny [player]     say no
/tpcancel            take your own request back
/tptoggle [on|off]   whether anybody may ask you
/tpablock <player>   whether one person may ask you
/tpaunblock <player> let them ask again
/back                undo the last teleport, or return to where you died
```

Aliases: `/tpask`, `/tphere`, `/tpyes`, `/tpno`, `/tpadeny`, `/tpacancel`, `/tpatoggle`, `/tpblock`,
`/tpunblock`. Only spellings of these commands — never a general word like `call`, because an alias
is registered server-wide and would take a name another plugin may be using.

## Answering by clicking

The player being asked gets the request in chat with the two answers on it:

```
Buddy wants to teleport to you.
  [Accept]   [Refuse]   [All requests]
```

Chat, deliberately, and not the action bar: a click event on the action bar does nothing, and this is
the one message in the plugin that has sixty seconds to be acted on. Typing `/tpaccept` does exactly
the same thing — the button is the same door at the place the player is already looking.

## Every feature is reachable both ways

`/tpa` with nobody named opens a hub with one button per feature, and every button is a command:

| | Command | Menu |
|---|---|---|
| Ask to go to somebody | `/tpa <player>` | a grid of faces — left-click |
| Ask somebody to come | `/tpahere <player>` | the same grid — right-click |
| Accept / refuse | `/tpaccept`, `/tpdeny` | *Requests*, left- and right-click |
| Take yours back | `/tpcancel` | *Requests*, click the outgoing one |
| Be left alone | `/tptoggle` | a lever on the hub |
| Block one person | `/tpablock`, `/tpaunblock` | *Blocked*, or shift-click a face |
| Go back | `/back` | a compass on the hub |

That is not a promise kept by hand: both front ends are generated from one enum (`TpaFeature`), and
`FeatureParityTest` fails the build if a constant names a command that is not registered, if a
registered command belongs to no button, or if two buttons want the same slot.

The player list shows, before you ask, whether somebody is even accepting requests — and never
whether *they* have blocked *you*, because the person who set that block has to live beside them
afterwards. A blocked player is told the same thing as anybody asking somebody who has requests off.

## One request out at a time

A player who types `/tpa` at four people in a row has not made four offers — they have changed their
mind three times, and the first three are traps for whoever accepts one. Sending a new request
withdraws the previous one, and the player who was asked is told, because a request that silently
stops working is worse than one that is refused.

Being *asked* is not capped: that is not something the person being asked did. A request stands for
sixty seconds and then lapses, and both ends are told, because both were told it existed.

`/tpaccept` with nobody named answers the most recent request and says whose it was, then says how
many are still waiting. Refusing to answer until the player types a name is correct and unhelpful —
the request has a minute to live.

## Not a way out of a fight

Whoever travels stands still for three seconds first; moving off the block or taking damage calls it
off. That is the whole reason teleport plugins get banned from PvP servers, and both halves are
settings — a peaceful server sets `warmup-seconds: 0`.

Turning your head is never "moving": the check is the block you started on, not the exact position.

The destination is read at the moment of the teleport, not when the request was accepted — the
player being travelled to keeps walking during the warmup, and arriving where they stood five seconds
ago is how somebody lands in the lava they just walked around.

## `/back`

One place per player, not a history: `/back` writes down where you were as it takes you away, so a
second `/back` returns you, and the whole feature is two points you can hold in your head.

**A death outranks a teleport.** Respawning is itself a teleport, so without that rule the death
point would be overwritten a tick after it was written and the command would be useless exactly when
it is wanted. Only a completed `/back`, or another death, replaces it.

Only teleports a player *asked for* are remembered — `PLUGIN`, `COMMAND` and `SPECTATE`. An ender
pearl, a nether portal and a chorus fruit are things you can walk back through, and remembering them
would mean `/back` usually undid the wrong thing.

Deliberately not on disk. A `/back` that survives a restart takes a player to where they stood before
the server went down, which is rarely where they think they are going.

## Configuration

```yaml
tpa:
  request-seconds: 60
  warmup-seconds: 3
  cancel-on-move: true
  cancel-on-damage: true
  cooldown-seconds: 5
  allow-cross-world: true
  operators-bypass: false
  back-enabled: true
  back-on-death: true
  back-cooldown-seconds: 10
  notify-sound: true
```

Numbers out of range are clamped rather than refused — a `warmup-seconds: -5` is a typo, and refusing
to start over it would be a worse answer than treating it as "no warmup".

`back-enabled: false` removes the command **and** its button, rather than leaving a button that
answers "that is disabled".

## The file

`plugins/RainsTPA/tpa.yml` — who has requests switched off, and who they have blocked, keyed by UUID
with the names beside them so an admin can read it. Requests themselves are never saved: an offer
that outlives the server the two players were on is not an offer.

Writes go through a single thread this plugin owns, not the server's schedulers, and the file is
written to a temporary name and moved into place, so the worst case of a crash mid-write is that the
last change is missing. A corrupt entry is skipped with a warning and everything else still loads.

## Folia

`folia-supported: true`. `Bukkit.getScheduler()` is never touched: the warmup and the request timer
both belong to a player and run on that player's own `EntityScheduler`, and saving happens off the
server's schedulers entirely.

## Permissions

| Node | Default | |
|---|---|---|
| `tpa.use` | everyone | ask, answer, and the menu |
| `tpa.back` | everyone | `/back` |
| `tpa.bypass.warmup` | nobody | teleport at once |
| `tpa.bypass.cooldown` | nobody | no wait between requests |
| `tpa.bypass.toggle` | nobody | ask somebody who has asked not to be asked |

The bypasses are deliberately **not** on for operators. An admin who inherits the warmup bypass sees
no warmup, decides the setting is broken, and the one person able to test the feature is the one it
never applies to. `operators-bypass: true` in the config is the switch for a server that wants it.

## Already running Rain's SMP Core?

This plugin is folded into it as the `tpa` module — **install one or the other, not both.** In that
build the settings move to the *Teleport requests* page of `/smpadmin`, short messages can go above
the hotbar instead of into chat, and the windows wear that plugin's brand. One file differs between
the two builds; see `MODULES.md` there.

## Building

```
mvn verify        # → target/RainsTPA-1.0.0.jar
```

Requires JDK 25: Paper 26.2 ships class files at version 69 and requires that at runtime, so
targeting anything lower would only pretend to be compatible.
