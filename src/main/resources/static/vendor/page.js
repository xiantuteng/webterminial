/* 快捷键 Ctrl+Alt+Shift+L 在 nginx 欢迎页和登录窗口之间切换。 */
(function () {
    var welcome = document.getElementById("welcome");
    if (!welcome) {
        return;
    }
    var open = false;
    var gate = null;
    var styleReady = false;

    document.addEventListener("keydown", function (event) {
        if (event.repeat) {
            return;
        }
        var toggle = event.ctrlKey && event.altKey && event.shiftKey && !event.metaKey && event.code === "KeyL";
        if (toggle) {
            event.preventDefault();
            if (open) {
                closeGate();
            } else {
                openGate();
            }
            return;
        }
        if (event.key === "Escape" && open) {
            event.preventDefault();
            closeGate();
        }
    });

    function openGate() {
        fetch("/wt/api/session", {credentials: "same-origin"})
            .then(function (response) { return response.json(); })
            .then(function (data) {
                if (data && data.authenticated) {
                    window.location.href = "/wt/console.html";
                    return;
                }
                showForm();
            })
            .catch(function () {
                showForm();
            });
    }

    function showForm() {
        ensureForm();
        welcome.hidden = true;
        gate.hidden = false;
        open = true;
        var username = gate.querySelector("input[name=username]");
        if (username) {
            username.focus();
        }
    }

    function closeGate() {
        if (!gate) {
            return;
        }
        var password = gate.querySelector("input[name=password]");
        var message = gate.querySelector(".msg");
        if (password) {
            password.value = "";
        }
        if (message) {
            message.textContent = "";
        }
        gate.hidden = true;
        welcome.hidden = false;
        open = false;
    }

    function ensureForm() {
        if (gate) {
            return;
        }
        if (!styleReady) {
            var style = document.createElement("style");
            style.textContent = [
                "#gate { margin-top: 4em; }",
                "#gate form { border: 1px solid #bbb; padding: 16px 18px 12px; }",
                "#gate .title { font-weight: bold; margin-bottom: 12px; }",
                "#gate label { display: block; }",
                "#gate input { display: block; width: 100%; box-sizing: border-box; margin: 4px 0 12px; font: inherit; padding: 4px 6px; }",
                "#gate button { font: inherit; padding: 4px 16px; }",
                "#gate .msg { min-height: 1.2em; color: #c00; }",
                "#gate .hint { color: #666; font-size: 0.9em; }"
            ].join("\n");
            document.head.appendChild(style);
            styleReady = true;
        }
        gate = document.createElement("div");
        gate.id = "gate";
        gate.hidden = true;
        gate.innerHTML = [
            "<form autocomplete=\"off\">",
            "<div class=\"title\">登录</div>",
            "<label>账号<input name=\"username\" maxlength=\"32\" autocomplete=\"username\" spellcheck=\"false\"></label>",
            "<label>密码<input name=\"password\" type=\"password\" maxlength=\"1024\" autocomplete=\"current-password\"></label>",
            "<div><button type=\"submit\">登录</button></div>",
            "<p class=\"msg\" role=\"alert\"></p>",
            "<p class=\"hint\">Esc 返回</p>",
            "</form>"
        ].join("");
        document.body.appendChild(gate);
        gate.querySelector("form").addEventListener("submit", submitLogin);
    }

    function submitLogin(event) {
        event.preventDefault();
        var form = event.target;
        var message = gate.querySelector(".msg");
        var button = form.querySelector("button");
        var username = form.username.value.trim();
        var password = form.password.value;
        if (!username || !password) {
            message.textContent = "请输入账号和密码";
            return;
        }
        button.disabled = true;
        message.textContent = "";
        fetch("/wt/api/login", {
            method: "POST",
            credentials: "same-origin",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                u: btoa(unescape(encodeURIComponent(username))),
                p: btoa(unescape(encodeURIComponent(password)))
            })
        }).then(function (response) {
            return response.json().then(function (data) {
                return {status: response.status, data: data};
            });
        }).then(function (result) {
            if (result.data && result.data.ok) {
                window.location.href = "/wt/console.html";
                return;
            }
            message.textContent = (result.data && result.data.message) || "账号或密码错误";
            button.disabled = false;
            form.password.focus();
        }).catch(function () {
            message.textContent = "无法连接服务";
            button.disabled = false;
        });
    }
})();
