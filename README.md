# Mineflayer

بلوجن ماينكرافت للسيرفرات.

## المنصات المدعومة

مبني ضد **Spigot API**، فيعمل على:

- Bukkit
- Spigot
- Paper
- Purpur وبقية الفوركات

ملف jar واحد يدعم سلسلتَي **26.1.x** و **26.2.x**.

## البناء

البناء يتم حصرياً على GitHub Actions عند الرفع إلى المستودع.

```bash
git push
```

ثم نزّل الـ artifact من تاب **Actions**.

## الأوامر

| الأمر | الوظيفة |
|---|---|
| `/mineflayer m start` | إدخال اللاعب الوهمي للسيرفر |
| `/mineflayer m stop` | إخراجه من السيرفر |

الاختصار `/mf` يعمل بدلاً من `/mineflayer`. الصلاحية: `mineflayer.command` (لِلأوبّ افتراضياً).

## اللاعب الوهمي

يُسجَّل اللاعب عبر `PlayerList.placeNewPlayer`، وهي نفس نقطة الدخول التي يستخدمها
تسجيل الدخول الحقيقي. لذلك يظهر في `Bukkit.getOnlinePlayers()` ويرفع عدد اللاعبين
`+1` ويمنع السيرفر من الدخول في حالة الخمول. يوضع في وضع `SPECTATOR` فلا جسم له
ولا يمكن التفاعل معه، وهو غير مرتبط بأي لاعب حقيقي.

الوصول إلى داخليات السيرفر (NMS) يتم بالـ reflection وليس باعتماد وقت-التصريف،
وهذا ما يحفظ عمل ملف jar واحد على Bukkit وSpigot وPaper والفوركات، وعلى
السلسلتين 26.1.x و26.2.x معاً. البحث عن الدوال يتم **بالشكل** (عدد المعاملات
وأنواعها) لا بالتوقيع الحرفي، لأن ماينكرافت تغيّر التوقيعات بين الإصدارات.

المقابل أن أي عدم تطابق يظهر وقت التشغيل لا وقت البناء، لذلك تُبلَّغ الأخطاء
لمُصدِر الأمر وتُسجَّل في الكونسول.

## المعلومات

- **الاسم:** Mineflayer
- **الصانع:** DevGBX9
- **الباكيج:** `com.devgbx9.mineflayer`
- **Java:** 25

## الهيكل

```
src/main/java/com/devgbx9/mineflayer/Mineflayer.java          - كلاس البلوجن
src/main/java/com/devgbx9/mineflayer/MineflayerCommand.java   - الأوامر
src/main/java/com/devgbx9/mineflayer/FakePlayerManager.java   - اللاعب الوهمي
src/main/java/com/devgbx9/mineflayer/NmsReflect.java          - أدوات الـ reflection
src/main/resources/plugin.yml                                 - بيانات البلوجن
```
