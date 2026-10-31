#!/usr/bin/env bash
# Полный прогон релиза:
#   сборка на ноде (ci/remote_build.sh, SHA-256 архива и APK) → копия в ~/Downloads →
#   MEGA (храним 2 версии) → site/release.json → коммит и push → сообщение в Discord.
# Секреты берутся из claude.md (mega login/password, вебхук) или из окружения.
# Использование: ci/release.sh ["что нового"]
set -euo pipefail
cd "$(dirname "$0")/.."
notes="${1:-}"
PY="${MEGA_PY:-$HOME/.venvs/wallet-mega/bin/python}"
SITE_URL="https://relkin016.github.io/root_voice_changer/"
secrets=claude.md
[ -f "$secrets" ] || secrets=CLAUDE.md

ci/remote_build.sh
version="$(bash ci/version.sh)"
apk="dist/RootVoiceChanger-$version.apk"
sha="$(cut -d' ' -f1 "$apk.sha256")"

dl="$HOME/Downloads"
mkdir -p "$dl"
cp "$apk" "$dl/"
(cd "$dl" && echo "$sha  $(basename "$apk")" | sha256sum -c -)

export MEGA_LOGIN="${MEGA_LOGIN:-$(sed -nE 's/^mega login:\s*(.+)$/\1/p' "$secrets")}"
export MEGA_PASSWORD="${MEGA_PASSWORD:-$(sed -nE 's/^mega password:\s*(.+)$/\1/p' "$secrets")}"
url="$("$PY" ci/mega_upload.py "$apk")"
echo "MEGA: $url"

tools/update_site.sh "$apk" "$url" >/dev/null
prev="$(python3 -I -c 'import json;p=json.load(open("site/release.json")).get("previous");print(p["version"] if p else "")')"
prev_url="$(python3 -I -c 'import json;p=json.load(open("site/release.json")).get("previous");print(p["url"] if p else "")')"

git add -A
git commit -q -m "release $version${prev:+ (previous $prev kept for rollback)}" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" || true
git push -q "${GIT_PUSH_URL:-git@github.com:relkin016/root_voice_changer.git}" HEAD:main

webhook="${DISCORD_WEBHOOK:-$(grep -oE 'https://discord.com/api/webhooks/[^ ]+' "$secrets" | head -1)}"
{
  echo "🎙️ **Root Voice Changer $version** собран и выложен"
  [ -n "$notes" ] && echo "$notes"
  echo "APK: $url"
  echo "SHA-256: \`$sha\`"
  echo "Размер: $(( $(stat -c %s "$apk") / 1024 )) КБ · подпись проверена, x86 нет"
  [ -n "$prev" ] && echo "Откат: $prev — $prev_url"
  echo "Сайт: $SITE_URL"
} | DISCORD_WEBHOOK="$webhook" python3 -I ci/discord.py
echo "готово: $version"
