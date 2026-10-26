SSH_D="$(command -v sshd || echo /usr/sbin/sshd)"; if [ "$(id -u)" = "0" ]; then mkdir -p /run/sshd; fi; pgrep -x sshd >/dev/null 2>&1 || "$SSH_D" -p {{port}}
