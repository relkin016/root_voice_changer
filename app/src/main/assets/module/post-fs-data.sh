#!/system/bin/sh
# До монтирования модулей: SELinux-контексты как у оригиналов (менеджер мог их сбросить),
# /odm монтируется вручную — магическое монтирование покрывает только /system и /vendor.
# Строка targets.txt: «файл в модуле|файл системы, с которого берётся контекст» (для обёртки
# драйвера и копии оригинала *.rvcorig.so это сам оригинальный audio.primary.*.so).
MODDIR=${0%/*}
# Модуль хранит копии файлов прошивки (audio_effects.xml, аудиодрайвер). После обновления прошивки
# они устарели: подкладывать их поверх новой нельзя — модуль не монтируется, пока его не пересоберут
# в приложении (оно покажет «прошивка обновилась»).
fp="$(getprop ro.build.fingerprint)|$(getprop ro.vendor.build.fingerprint)"
if [ "$(cat "$MODDIR/fingerprint.txt" 2>/dev/null)" != "$fp" ]; then
  touch "$MODDIR/skip_mount"
  exit 0
fi
rm -f "$MODDIR/skip_mount"
while IFS='|' read -r rel orig; do
  [ -n "$rel" ] && [ -f "$MODDIR/$rel" ] && [ -f "$orig" ] || continue
  ctx=$(ls -Z "$orig" | awk '{print $1}')
  case "$ctx" in u:*) chcon "$ctx" "$MODDIR/$rel" ;; esac
  chmod 0644 "$MODDIR/$rel"
  case "$rel" in odm/*) mount --bind "$MODDIR/$rel" "$orig" ;; esac
done < "$MODDIR/targets.txt"
for d in "$MODDIR"/system/vendor/lib*/soundfx; do
  [ -d "$d" ] && chcon -R u:object_r:vendor_file:s0 "$d"
done
for d in "$MODDIR"/system/lib*/soundfx; do
  [ -d "$d" ] && chcon -R u:object_r:system_file:s0 "$d"
done
[ -f /data/adb/rootvoice/params ] && setprop debug.rvc.params "$(cat /data/adb/rootvoice/params)"
