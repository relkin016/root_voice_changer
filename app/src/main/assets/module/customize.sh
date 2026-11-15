# Выполняется root-менеджером при установке.
ui_print "- Root Voice Changer: аудиоэффект для микрофона"
ui_print "- Подменяемые файлы: $(cut -d'|' -f1 "$MODPATH/targets.txt" | tr '\n' ' ')"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for d in "$MODPATH"/system/vendor/lib*/soundfx "$MODPATH"/system/vendor/lib*/hw; do
  [ -d "$d" ] && set_perm_recursive "$d" 0 0 0755 0644 u:object_r:vendor_file:s0
done
for f in $(find "$MODPATH/system/vendor/etc" "$MODPATH/odm" -name '*.xml' 2>/dev/null); do
  set_perm "$f" 0 0 0644 u:object_r:vendor_configs_file:s0
done
ui_print "- Перезагрузите телефон, затем настройте голос в приложении"
