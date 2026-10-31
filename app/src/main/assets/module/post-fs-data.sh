#!/system/bin/sh
# До монтирования модулей: SELinux-контексты как у оригиналов (менеджер мог их сбросить),
# /odm монтируется вручную — магическое монтирование покрывает только /system и /vendor.
MODDIR=${0%/*}
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
