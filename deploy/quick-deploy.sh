#!/bin/bash
# ==========================================
# 家庭管理系统 - 超级一键部署脚本
# 用法：bash quick-deploy.sh
# ==========================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$SCRIPT_DIR"

cd "$DEPLOY_DIR"

# ========== 自我更新 ==========
if [ -d "$PROJECT_DIR/.git" ]; then
    cd "$PROJECT_DIR"
    if git fetch origin main > /dev/null 2>&1; then
        LOCAL=$(git rev-parse HEAD)
        REMOTE=$(git rev-parse origin/main)
        
        if [ "$LOCAL" != "$REMOTE" ]; then
            echo -e "${YELLOW}🔄 检测到脚本有更新，正在自动更新...${NC}"
            git checkout origin/main -- deploy/quick-deploy.sh
            echo -e "${GREEN}✓ 脚本已更新，重新执行...${NC}"
            exec bash "$DEPLOY_DIR/quick-deploy.sh"
        fi
    fi
    cd "$DEPLOY_DIR"
fi

clear

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════════╗"
echo "║                                              ║"
echo "║   🏠  家庭管理系统 - 一键部署  🏠            ║"
echo "║                                              ║"
echo "╚══════════════════════════════════════════════╝"
echo -e "${NC}"
echo ""

# 检查是否为 root
if [ "$EUID" -ne 0 ]; then 
    echo -e "${YELLOW}⚠️  检测到非 root 用户，建议使用 root 执行${NC}"
    echo -e "${YELLOW}   如果权限不够，请切换到 root：sudo -i${NC}"
    echo ""
fi

# ========== 第一步：环境检测 ==========
echo -e "${BLUE}${BOLD}📦 [1/7] 环境检测与安装${NC}"
echo "──────────────────────────────────────────────"

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo -e "${YELLOW}  Docker 未安装，正在安装...${NC}"
    
    if ! apt-get update -qq; then
        echo -e "${YELLOW}  国内源更新失败，尝试使用阿里云源...${NC}"
        mv /etc/apt/sources.list /etc/apt/sources.list.bak
        cat > /etc/apt/sources.list <<'EOF'
deb http://mirrors.aliyun.com/ubuntu/ jammy main restricted universe multiverse
deb http://mirrors.aliyun.com/ubuntu/ jammy-updates main restricted universe multiverse
deb http://mirrors.aliyun.com/ubuntu/ jammy-backports main restricted universe multiverse
deb http://mirrors.aliyun.com/ubuntu/ jammy-security main restricted universe multiverse
EOF
        apt-get update -qq
    fi
    
    apt-get install -y -qq curl ca-certificates gnupg lsb-release
    
    curl -fsSL https://get.docker.com | bash || \
    (echo -e "${YELLOW}  get.docker.com 无法访问，使用阿里云源安装...${NC}" && \
     curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg && \
     echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list && \
     apt-get update -qq && \
     apt-get install -y -qq docker-ce docker-ce-cli containerd.io)
    
    if command -v docker &> /dev/null; then
        systemctl start docker
        systemctl enable docker
        echo -e "${GREEN}  ✓ Docker 安装完成${NC}"
    else
        echo -e "${RED}  ✗ Docker 安装失败！请手动安装后重试${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}  ✓ Docker 已安装：$(docker --version | cut -d' ' -f3)${NC}"
fi

# 检查 Docker Compose
if ! docker compose version &> /dev/null; then
    echo -e "${YELLOW}  Docker Compose 插件未安装，正在安装...${NC}"
    apt-get install -y -qq docker-compose-plugin
    echo -e "${GREEN}  ✓ Docker Compose 安装完成${NC}"
else
    echo -e "${GREEN}  ✓ Docker Compose 已安装：$(docker compose version | cut -d' ' -f4)${NC}"
fi

# 配置 Docker 镜像加速
if [ ! -f /etc/docker/daemon.json ]; then
    mkdir -p /etc/docker
    cat > /etc/docker/daemon.json <<'EOF'
{
    "registry-mirrors": [
        "https://registry.docker-cn.com",
        "https://docker.mirrors.ustc.edu.cn"
    ],
    "log-driver": "json-file",
    "log-opts": {
        "max-size": "100m",
        "max-file": "3"
    }
}
EOF
    systemctl daemon-reload
    systemctl restart docker
    echo -e "${GREEN}  ✓ Docker 镜像加速已配置${NC}"
fi

echo ""

# ========== 第二步：拉取最新代码 ==========
echo -e "${BLUE}${BOLD}🔄 [2/7] 拉取最新代码${NC}"
echo "──────────────────────────────────────────────"

cd "$PROJECT_DIR"

if [ -d ".git" ]; then
    echo -e "${YELLOW}  正在拉取最新代码...${NC}"
    git fetch origin
    git reset --hard origin/main
    echo -e "${GREEN}  ✓ 代码已更新到最新版本${NC}"
else
    echo -e "${YELLOW}  ⚠️  未检测到 Git 仓库，请手动上传代码${NC}"
    echo -e "${YELLOW}     或执行：git init && git remote add origin https://github.com/mayonghui9182/family-app.git${NC}"
    echo -e "${YELLOW}     然后重新运行本脚本${NC}"
    echo ""
    read -p "  是否继续？(y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

cd "$DEPLOY_DIR"

echo ""

# ========== 第三步：配置环境变量 ==========
echo -e "${BLUE}${BOLD}⚙️  [3/7] 配置环境变量${NC}"
echo "──────────────────────────────────────────────"

if [ ! -f .env ]; then
    echo -e "${YELLOW}  未找到 .env 文件，正在自动生成...${NC}"
    
    # 生成随机密码和密钥
    DB_PASSWORD=$(openssl rand -base64 16 | tr -d /=+ | cut -c1-24)
    JWT_SECRET=$(openssl rand -base64 48 | tr -d /=+ | cut -c1-64)
    
    cp .env.example .env
    sed -i "s/your_strong_password_here/$DB_PASSWORD/" .env
    sed -i "s|please_change_this_to_a_long_random_string_2024|$JWT_SECRET|" .env
    
    echo -e "${GREEN}  ✓ 已生成随机数据库密码${NC}"
    echo -e "${GREEN}  ✓ 已生成随机 JWT 密钥${NC}"
    echo ""
    echo -e "${YELLOW}  📝 配置已保存到 .env 文件${NC}"
    echo -e "${YELLOW}     数据库密码：$DB_PASSWORD${NC}"
    echo -e "${YELLOW}     （如需修改，请编辑 .env 文件）${NC}"
else
    echo -e "${GREEN}  ✓ .env 文件已存在，跳过${NC}"
fi

echo ""

# ========== 第四步：构建后端 ==========
echo -e "${BLUE}${BOLD}☕ [4/7] 构建后端 (Java)${NC}"
echo "──────────────────────────────────────────────"

# 检查 Java（精确匹配主版本号21）
JAVA_MAJOR_VERSION=""
if command -v java &> /dev/null; then
    JAVA_MAJOR_VERSION=$(java -version 2>&1 | head -1 | awk -F['"'] '{print $2}' | cut -d'.' -f1)
fi
if [ "$JAVA_MAJOR_VERSION" != "21" ]; then
    echo -e "${YELLOW}  JDK 21 未安装（当前: ${JAVA_MAJOR_VERSION:-无}），正在安装...${NC}"
    
    if apt-cache search openjdk-21-jdk-headless | grep -q "openjdk-21-jdk-headless"; then
        apt-get install -y -qq openjdk-21-jdk-headless
    else
        apt-get install -y -qq wget apt-transport-https gnupg
        wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium-archive-keyring.gpg
        echo "deb [signed-by=/usr/share/keyrings/adoptium-archive-keyring.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list
        apt-get update -qq
        apt-get install -y -qq temurin-21-jdk
        echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64' >> /etc/profile
        echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile
        export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
        export PATH=$JAVA_HOME/bin:$PATH
    fi
    
    echo -e "${GREEN}  ✓ JDK 21 安装完成${NC}"
fi

# 检查 Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${YELLOW}  Maven 未安装，正在安装...${NC}"
    apt-get install -y -qq maven
    echo -e "${GREEN}  ✓ Maven 安装完成${NC}"
fi

# 配置阿里云 Maven 镜像（强制覆盖，确保配置生效）
MAVEN_SETTINGS_DIR="$HOME/.m2"
MAVEN_SETTINGS_FILE="$MAVEN_SETTINGS_DIR/settings.xml"
mkdir -p "$MAVEN_SETTINGS_DIR"
cp -f "$DEPLOY_DIR/conf/settings.xml" "$MAVEN_SETTINGS_FILE"
echo -e "${GREEN}  ✓ 阿里云 Maven 镜像已配置${NC}"

echo -e "${YELLOW}  正在构建后端（代码已更新，强制重新构建）...${NC}"
cd "$PROJECT_DIR/backend"
mvn clean package -DskipTests 2>&1 | tee /tmp/maven-build.log
MVN_EXIT=${PIPESTATUS[0]}
cd "$DEPLOY_DIR"

if [ "$MVN_EXIT" -ne 0 ]; then
    echo -e "${RED}  ✗ 后端构建失败！${NC}"
    echo -e "${YELLOW}  详细日志：cat /tmp/maven-build.log${NC}"
    echo -e "${YELLOW}  请手动执行：cd backend && mvn clean package -DskipTests${NC}"
    exit 1
fi

JAR_FILE=$(find "$PROJECT_DIR/backend/target" -name "*.jar" 2>/dev/null | grep -v sources | grep -v original | head -1)

if [ -n "$JAR_FILE" ]; then
    echo -e "${GREEN}  ✓ 后端构建成功${NC}"
else
    echo -e "${RED}  ✗ 后端构建失败！未找到 JAR 文件${NC}"
    echo -e "${YELLOW}  详细日志：cat /tmp/maven-build.log${NC}"
    exit 1
fi

echo ""

# ========== 第五步：构建前端 ==========
echo -e "${BLUE}${BOLD}💻 [5/7] 构建前端 (Vue)${NC}"
echo "──────────────────────────────────────────────"

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo -e "${YELLOW}  Node.js 未安装，正在安装...${NC}"
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - > /dev/null 2>&1
    apt-get install -y -qq nodejs
    echo -e "${GREEN}  ✓ Node.js 安装完成${NC}"
fi

# 检查 pnpm
if ! command -v pnpm &> /dev/null; then
    npm install -g pnpm > /dev/null 2>&1
    echo -e "${GREEN}  ✓ pnpm 安装完成${NC}"
fi

echo -e "${YELLOW}  安装依赖中...${NC}"
cd "$PROJECT_DIR/frontend"
pnpm install --frozen-lockfile 2>&1 | tee /tmp/pnpm-install.log || pnpm install 2>&1 | tee /tmp/pnpm-install.log
PNPM_EXIT=${PIPESTATUS[0]}

if [ "$PNPM_EXIT" -ne 0 ]; then
    echo -e "${RED}  ✗ 前端依赖安装失败！${NC}"
    echo -e "${YELLOW}  详细日志：cat /tmp/pnpm-install.log${NC}"
    exit 1
fi

echo -e "${YELLOW}  构建中...${NC}"
pnpm build 2>&1 | tee /tmp/pnpm-build.log
BUILD_EXIT=${PIPESTATUS[0]}
cd "$DEPLOY_DIR"

if [ "$BUILD_EXIT" -ne 0 ] || [ ! -d "$PROJECT_DIR/frontend/dist" ]; then
    echo -e "${RED}  ✗ 前端构建失败！${NC}"
    echo -e "${YELLOW}  详细日志：cat /tmp/pnpm-build.log${NC}"
    exit 1
fi
echo -e "${GREEN}  ✓ 前端构建成功${NC}"

echo ""

# ========== 第六步：启动服务 ==========
echo -e "${BLUE}${BOLD}🚀 [6/7] 启动服务${NC}"
echo "──────────────────────────────────────────────"

# 停止旧服务（如果有）
if docker compose ps -q &> /dev/null && [ -n "$(docker compose ps -q)" ]; then
    echo -e "${YELLOW}  停止旧服务...${NC}"
    docker compose down > /dev/null 2>&1
fi

echo -e "${YELLOW}  启动服务中...${NC}"
docker compose up -d --build 2>&1 | tee /tmp/docker-deploy.log
COMPOSE_EXIT=${PIPESTATUS[0]}

if [ "$COMPOSE_EXIT" -ne 0 ]; then
    echo -e "${RED}  ✗ 服务启动失败！${NC}"
    echo -e "${YELLOW}  详细日志：cat /tmp/docker-deploy.log${NC}"
    exit 1
fi

echo -e "${GREEN}  ✓ 服务已启动${NC}"

echo ""

# ========== 第七步：等待就绪 ==========
echo -e "${BLUE}${BOLD}⏳ [7/7] 等待服务就绪${NC}"
echo "──────────────────────────────────────────────"

# 获取数据库配置
source .env
DB_USER=${POSTGRES_USER:-family}
DB_NAME=${POSTGRES_DB:-family_app}

# 等待数据库
echo -n "  数据库启动中"
for i in {1..30}; do
    if docker exec family-postgres pg_isready -U "$DB_USER" -d "$DB_NAME" &> /dev/null; then
        echo -e " ${GREEN}✓ 就绪${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

# 等待后端
echo -n "  后端启动中  "
BACKEND_READY=0
for i in {1..60}; do
    # 检查容器日志是否有启动完成标志
    if docker logs family-backend 2>&1 | grep -q "Started.*Application\|JVM running for"; then
        echo -e "${GREEN}✓ 就绪${NC}"
        BACKEND_READY=1
        break
    fi
    # 也可以通过 HTTP 检查
    if curl -s http://127.0.0.1:8080/ &> /dev/null; then
        echo -e "${GREEN}✓ 就绪${NC}"
        BACKEND_READY=1
        break
    fi
    echo -n "."
    sleep 2
done

if [ "$BACKEND_READY" -eq 0 ]; then
    echo -e "${YELLOW}  ⚠️  后端启动较慢，请稍后查看日志确认${NC}"
fi

# 等待前端
echo -n "  前端启动中  "
for i in {1..10}; do
    if curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:80/ | grep -q "200\|301\|302"; then
        echo -e "${GREEN}✓ 就绪${NC}"
        break
    fi
    echo -n "."
    sleep 1
done

echo ""

# ========== 完成 ==========
echo ""
echo -e "${GREEN}${BOLD}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}${BOLD}║                                              ║${NC}"
echo -e "${GREEN}${BOLD}║        🎉  部署完成！🎉                       ║${NC}"
echo -e "${GREEN}${BOLD}║                                              ║${NC}"
echo -e "${GREEN}${BOLD}╚══════════════════════════════════════════════╝${NC}"
echo ""

# 获取服务器 IP
SERVER_IP=$(curl -s ifconfig.me 2>/dev/null || curl -s ipinfo.io/ip 2>/dev/null || hostname -I | awk '{print $1}')

echo -e "🌐 访问地址：${CYAN}http://$SERVER_IP${NC}"
echo ""
echo -e "📊 服务状态："
docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || docker compose ps
echo ""
echo -e "📝 常用命令："
echo -e "  查看日志：  ${YELLOW}cd $DEPLOY_DIR && docker compose logs -f${NC}"
echo -e "  重启服务：  ${YELLOW}cd $DEPLOY_DIR && docker compose restart${NC}"
echo -e "  停止服务：  ${YELLOW}cd $DEPLOY_DIR && docker compose down${NC}"
echo -e "  备份数据：  ${YELLOW}bash $DEPLOY_DIR/scripts/backup.sh${NC}"
echo ""
echo -e "💡 下一步："
echo -e "  1. 打开浏览器访问 http://$SERVER_IP"
echo -e "  2. 点击「创建家庭」开始使用"
echo -e "  3. 邀请家人加入，共同管理宝宝成长"
echo ""

# 保存配置信息
echo "服务器IP: $SERVER_IP" > "$DEPLOY_DIR/deploy-info.txt"
echo "部署时间: $(date)" >> "$DEPLOY_DIR/deploy-info.txt"
echo "" >> "$DEPLOY_DIR/deploy-info.txt"
echo "数据库配置:" >> "$DEPLOY_DIR/deploy-info.txt"
echo "  用户: $DB_USER" >> "$DEPLOY_DIR/deploy-info.txt"
echo "  数据库: $DB_NAME" >> "$DEPLOY_DIR/deploy-info.txt"
echo "  密码: 见 .env 文件" >> "$DEPLOY_DIR/deploy-info.txt"

echo -e "${CYAN}💾 部署信息已保存到 deploy/deploy-info.txt${NC}"
echo ""
