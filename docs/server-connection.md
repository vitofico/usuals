# Connect to a server and share lists

Usuals works offline without an account. Signing in to a Usuals server keeps every
list of your account on that server: the phone and the web app show the same
lists, and a change on one appears on the other within a second or two. The phone
keeps an offline copy and sends its changes when it can reach the server again.

## Connect your phone from a computer

1. Sign in to the web app and open **Account > Connect a phone**. It shows a QR.
2. On the phone, open **Settings > Account** and choose **Scan the QR from the web
   app**, or scan the code with the phone's camera: the server's `/connect` page
   offers **Open in Usuals**.
3. The phone asks once, showing the server address: **Connect** or **Not now**.
4. The phone connects straight away, with no code to compare, and the web app
   shows **Phone connected**.

Because scanning is enough, keep the QR to yourself while it is shown. It works
for one phone and expires after 10 minutes; **Create a new code** replaces it.

## Connect without a computer

Under **Settings > Account > No computer at hand?**, enter the server address and
choose **Sign in in your browser**. Sign in on the server's page, check that the
phone and the browser show the same six-digit code, and approve. The browser page
ends with **Return to Usuals**, and the app notices the approval on its own.

## What happens to the lists on the phone

When the phone connects, every list on it is saved to your account and stays in
sync from then on. Lists already in your account appear on the phone. Signed out,
the lists never leave the phone.

**Sign out** on the Account screen first sends what is pending. If some changes
could not reach the server yet, the app says how many and asks before signing out
anyway. Signing out removes the account's lists from the phone; they stay in your
account, and the app starts a new list that stays on the phone. If the server stops
accepting the phone (for example after it was disconnected from the web app), the
app asks you to **Sign in again** and keeps everything on the phone until you do
or sign out.

## Share a list

Choose **Share** on a list, on the phone or in the web app. One sheet shows:

- **People with access**: the owner and everyone added. The owner can **Remove**
  a person; anyone else can **Leave list** from their own row.
- **Add people**: search the other accounts on the same server by name or email
  and add them. They see the list the next time their phone or browser checks in,
  and can edit it and add others too.

Only the owner can remove people or delete the list. The list menu also holds
**Send as text**, **Rename** and either **Delete list** (owner) or **Leave list**.
There are no invitations, codes or links to send. Sharing works between accounts
on the same server; signed out, the Share sheet explains that sharing needs an
account and opens **Account**.

Everyone with access sees changes within seconds. When two people change the same
thing at once, the server keeps one answer. The web app shows a short notice when
its change lost; the phone lists such changes for review.

## Connected phones

**Account > Connected phones** in the web app lists every phone signed in to your
account. **Disconnect** signs one out; its lists stay on the server. A phone stays
connected for 180 days after its last use, and a browser session for 30 days.

## Server AI

A signed-in phone can use the server's AI. The Account screen shows the provider,
model and processing location, with one **AI assistance** switch. Imports always
need an explicit model action and a review before items are added. Changed server
AI settings need a fresh confirmation. On the web, optional personal provider and
key controls live under **Account > AI assistance > Advanced**. Direct providers
configured on the phone stay available under **Settings > Advanced: direct AI**
while signed out, and no saved Keystore key is uploaded to the server.

## Updating from household sharing

Earlier versions shared lists through end-to-end encrypted households. Updating
the app keeps every list on the phone, old shared lists included, as ordinary
lists. A phone that was already connected uploads all its lists on its next sync.
When two phones held the same old shared list, the first upload becomes the
account's list and the second phone's copy becomes a separate list its owner can
share again. Lists that existed only in the browser are gone: the web app deletes
the old browser storage on load.

A server with server-held lists answers older app versions with an update
request, so update Usuals on every phone.
