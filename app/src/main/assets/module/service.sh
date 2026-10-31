#!/system/bin/sh
# Восстанавливает последние параметры голоса после загрузки.
[ -f /data/adb/rootvoice/params ] && setprop debug.rvc.params "$(cat /data/adb/rootvoice/params)"
