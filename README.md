# Swipe Clean (Android)

Swipe right = delete (moves to Android's system Trash, restorable ~30 days). Swipe left = keep.

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo and push these files (including the hidden `.github` folder).
2. Open the repo > Actions > "Build APK" > wait for the green tick (about 3-5 minutes).
3. Open the finished run > Artifacts > download `swipe-clean-apk`, unzip it, send `app-debug.apk` to your phone.
4. Install it (allow "Install unknown apps" when asked), open it, and choose "Allow" for photos.

Needs Android 11 or newer.

## Tips
- Menu > "Delete without popups": turn on the switch for this app (Android 12+). After that, every swipe-right trashes the photo instantly.
- Menu > "Choose album": clean just Screenshots, WhatsApp Images, Camera, etc.
- Menu > "Order": newest, oldest, largest first (best for freeing space fast), or random.
- A quick flick left or right also works, you don't have to drag the whole way.
- v1.3: next 3 photos are preloaded, the next photo is already visible under the top card, and you can swipe again right away (no waiting for the animation).
