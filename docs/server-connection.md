# Connect a server and share a list

Android has one place for this: **Settings > Server and sharing**, also reached
from the sharing icon on the list. It shows only the current step.

## Start sharing (the first phone)

1. Under **Start sharing**, scan the server's QR code or type its address, then
   tap **Continue**. The QR is on the server's website under **Account >
   Connect a phone**; scanning it only fills in the address.
2. Tap **Sign in in your browser**. The phone and the browser show the same
   six-digit code; approve in the browser when they match. The browser page ends
   with **Return to Usuals**, and the app notices the approval on its own. An
   attempt survives switching apps or restarting the phone, and an expired one
   can be cancelled and started again.
3. Usuals then drafts the household. Tap **Save recovery file**: the app writes
   the file, reads it back and checks it, so there is nothing to paste. Copy the
   recovery key, keep it somewhere other than the file, tick **I stored the
   recovery key somewhere safe** and tap **Create household**.
4. When started from a list's sharing icon, the list preview opens next. History
   is off by default. An interrupted share reuses the prepared copy and its
   history choice; the original local list is kept.

## Invite and join

The owner taps **Invite someone**. A QR code and a share button appear; the
sheet stays open and turns into "A phone wants to join, code 482 913" with
**Approve** and **Deny** once the other person scans. A request that arrives
after the sheet was closed shows on the household card.

The other person scans the QR in Usuals (**Join with an invite**), or with the
phone camera, which opens the server's `/join` page and its **Open in Usuals**
button. A link sent by message works the same way when pasted. They check the
server name and tap **Join**; their screen shows the same six digits while it
waits. Reading the digits over a call is optional and useful when the link
travelled through a channel you do not fully trust.

An invite works once and expires after 24 hours. Only the phone or browser that
created the invite can approve its request; another owner device shows the
request and says where to approve it. Signing in to the server alone never
grants household keys.

## Server AI and the account

A connected account can use the server's AI while lists stay local. The server
card shows the provider, model and processing location, with one switch. Imports
always need an explicit model action and a review before items are added. AI
consent can be turned off while offline, and changed server AI settings need a
fresh confirmation. On the web, optional personal provider and key controls live
under **Account > AI assistance > Advanced**.

Account connections expire after 30 days. **Advanced > Disconnect account
services** keeps household shopping work; offline it removes only the local
token, so use **Account > Connected phones** in the browser for immediate remote
revocation. Existing direct native provider settings are kept and no saved
Keystore key is uploaded to the server.

## Advanced

Everything rare sits under a collapsed **Advanced** section: key rotation,
recovery kit replacement, ownership transfer, recovery from a file, frozen copies,
saving unsent work, resetting unreadable sharing keys, signing in for server AI
as a member, joining a second household and disconnecting. The manual setup path
with an administrator code remains there for servers without accounts.

**Settings** shows one **Server and sharing** row: "Not set up", or the server
host and how many phones share through it.

A server must support connection protocol 1 and migration 006 for this flow.
Older app versions keep working for existing households but report "update
needed" for new invites.
