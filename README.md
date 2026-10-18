# SpirOS

**مساحتك الثانية المعزولة داخل هاتفك.** تطبيقات أندرويد معزولة، وخزنة مشفرة، وتصفح ضد التتبع، ومحرك نظام وهمي تجريبي، كل ذلك بدون روت.

*Your second, isolated space on Android: sandboxed apps, an encrypted vault, tracker-free browsing and an experimental VM engine. No root required.*

- 🗺️ خريطة الطريق: [`docs/ROADMAP.md`](docs/ROADMAP.md)
- 📦 تحميل آخر نسخة: تبويب **Actions** ← "Build APK" ← Artifacts ← `SpirOS-debug-apk`

## البناء

```bash
./gradlew assembleDebug
```

يتطلب JDK 17 وAndroid SDK 35 وNDK (يثبّتها Android Studio تلقائيًا). كل push يُبنى تلقائيًا عبر GitHub Actions.

## الترخيص

GPL-2.0-or-later، انظر [`LICENSE`](LICENSE). هذا الترخيص متوافق مع QEMU الذي يستخدمه محرك المختبر.
