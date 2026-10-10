# ============================================================
# Jotlog 构建脚本
#
# 之前这里是 Go 的 Makefile（build / build-linux-amd64 等），
# 换Java + Spring Boot 后已全部重写。旧目标不再适用。
# ============================================================

MVN      := mvn
JAR      := target/jotlog.jar
PROFILE  := local
WEB      := web
# npm 必须跑在 Node 20+ 上（Vite 6 / Tailwind 4 的硬性要求）。
# 默认 node 版本太低时用 NODE_HOME 指过去：
#   make web-build NODE_HOME="C:/Users/you/.workbuddy/binaries/node/versions/22.22.2-6"
NODE_BIN := $(if $(NODE_HOME),$(NODE_HOME)/,)
NPM      := $(NODE_BIN)npm

.DEFAULT_GOAL := help

.PHONY: help
help: ## 显示可用命令
	@echo "Jotlog 可用命令："
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

.PHONY: compile
compile: ## 编译
	$(MVN) -B clean compile

.PHONY: test
test: ## 跑单元测试
	$(MVN) -B test

.PHONY: package
package: ## 打包成可执行 jar（不含前端，web/dist 存在时会被自动打进去）
	$(MVN) -B clean package -DskipTests
	@echo "产物：$(JAR)"

.PHONY: dist
dist: web-build package ## 前端 + 后端，产出带界面的完整 jar
	@echo "产物：$(JAR)（已包含 web/dist 的前端）"

.PHONY: run
run: ## 本地启动（后端 8095；前端另开 make web-dev）
	$(MVN) spring-boot:run

.PHONY: verify
verify: ## 打包并跑测试，提交前用这个
	$(MVN) -B clean verify

# ---------- 数据库 ----------

.PHONY: db-init
db-init: ## 初始化数据库（会提示输入密码）
	@echo "请先确认 scripts/init-db.sql 里的密码已改掉"
	mysql -u root -p< scripts/init-db.sql

.PHONY: db-check
db-check: ## 检查 ngram 全文索引是否正常
	@mysql -u root -p jotlog -e "\
	SELECT VERSION() AS mysql_version, \
	       @@ngram_token_size AS ngram_token_size, \
	       @@character_set_server AS charset;"

# ---------- 前端 ----------

.PHONY: web-install
web-install: ## 安装前端依赖
	cd $(WEB) && $(NPM) install

.PHONY: web-dev
web-dev: ## 前端开发服务器（5190），/api 代理到本机 8095
	cd $(WEB) && $(NPM) run dev

.PHONY: web-build
web-build: ## 构建前端到 web/dist
	cd $(WEB) && $(NPM) run build

# ---------- 部署 ----------

.PHONY: install
install: package ## 安装到服务器
	@echo "上传 $(JAR) 到服务器 /opt/jotlog/，然后："
	@echo "  sudo cp scripts/jotlog.service /etc/systemd/system/"
	@echo "  sudo systemctl daemon-reload"
	@echo "  sudo systemctl enable --now jotlog"
	@echo "  journalctl -u jotlog -f"

.PHONY: clean
clean: ## 清理
	$(MVN) -B clean
