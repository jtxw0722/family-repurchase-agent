pipeline {
    agent any

    parameters {
        string(
            name: 'MCP_RUNTIME_DIR',
            defaultValue: 'D:\\mcp-runtime\\family-repurchase-agent',
            description: 'Local runtime directory for published MCP Server jar. Used by local Agent Host such as Claude Code.'
        )
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
    }

    environment {
        APP_NAME = 'family-repurchase-agent'
        SERVICE_NAME = 'family-repurchase-agent'

        BACKEND_LOCAL_JAR = 'target\\family-repurchase-agent.jar'
        MCP_LOCAL_JAR = 'adapters\\mcp\\family-repurchase-mcp-java-server\\target\\family-repurchase-mcp-java-server.jar'
        MCP_RUNTIME_JAR_NAME = 'family-repurchase-mcp-java-server.jar'

        REMOTE_TMP_JAR = '/tmp/family-repurchase-agent.jar'
        REMOTE_APP_JAR = '/opt/family-repurchase-agent/app/family-repurchase-agent.jar'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Backend') {
            steps {
                bat encoding: 'UTF-8', script: '''
                @echo off
                chcp 65001 >NUL

                set MAVEN_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Duser.language=en -Duser.country=US

                mvn clean package -DskipTests
                if errorlevel 1 exit /b %errorlevel%
                '''
            }
        }

        stage('Build MCP Server') {
            steps {
                bat encoding: 'UTF-8', script: '''
                @echo off
                chcp 65001 >NUL
                set MAVEN_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Duser.language=en -Duser.country=US

                mvn -f adapters\\mcp\\family-repurchase-mcp-java-server\\pom.xml clean package -DskipTests
                if errorlevel 1 exit /b %errorlevel%
                '''
            }
        }

        stage('Validate Build Artifacts') {
            steps {
                bat encoding: 'UTF-8', script: '''
                @echo off
                chcp 65001 >NUL

                echo ===== Validate build artifacts =====

                if not exist "%BACKEND_LOCAL_JAR%" (
                    echo Backend jar not found: %BACKEND_LOCAL_JAR%
                    exit /b 1
                )

                if not exist "%MCP_LOCAL_JAR%" (
                    echo MCP jar not found: %MCP_LOCAL_JAR%
                    exit /b 1
                )

                echo Backend jar: %BACKEND_LOCAL_JAR%
                echo MCP jar: %MCP_LOCAL_JAR%
                '''
            }
        }

        stage('Publish MCP Server Locally') {
            steps {
                bat encoding: 'UTF-8', script: '''
                @echo off
                chcp 65001 >NUL
                setlocal EnableExtensions

                set "MCP_SOURCE_JAR=%MCP_LOCAL_JAR%"
                set "MCP_RUNTIME_JAR=%MCP_RUNTIME_DIR%\\%MCP_RUNTIME_JAR_NAME%"

                echo ===== Publish MCP Server locally =====
                echo MCP_SOURCE_JAR=%MCP_SOURCE_JAR%
                echo MCP_RUNTIME_DIR=%MCP_RUNTIME_DIR%
                echo MCP_RUNTIME_JAR=%MCP_RUNTIME_JAR%

                if "%MCP_RUNTIME_DIR%"=="" (
                    echo MCP_RUNTIME_DIR is empty.
                    exit /b 1
                )

                if not exist "%MCP_SOURCE_JAR%" (
                    echo MCP_SOURCE_JAR not found: %MCP_SOURCE_JAR%
                    exit /b 1
                )

                if not exist "%MCP_RUNTIME_DIR%" (
                    mkdir "%MCP_RUNTIME_DIR%"
                    if errorlevel 1 exit /b %errorlevel%
                )

                copy /Y "%MCP_SOURCE_JAR%" "%MCP_RUNTIME_JAR%"
                if errorlevel 1 exit /b %errorlevel%

                echo MCP Server published to %MCP_RUNTIME_JAR%
                '''
            }
        }

        stage('Deploy Backend') {
            steps {
                withCredentials([
                    string(credentialsId: 'family-repurchase-server-host', variable: 'DEPLOY_HOST'),
                    sshUserPrivateKey(
                        credentialsId: 'family-repurchase-deploy-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'DEPLOY_USER'
                    )
                ]) {
                    bat encoding: 'UTF-8', script: '''
                    @echo off
                    chcp 65001 >NUL
                    setlocal EnableExtensions

                    echo ===== Validate local jar =====
                    if not exist "%BACKEND_LOCAL_JAR%" (
                        echo BACKEND_LOCAL_JAR not found: %BACKEND_LOCAL_JAR%
                        exit /b 1
                    )

                    echo ===== Fix SSH private key ACL =====
                    icacls "%SSH_KEY%" /inheritance:r >NUL
                    if errorlevel 1 exit /b %errorlevel%

                    icacls "%SSH_KEY%" /remove:g "*S-1-5-32-545" "*S-1-5-11" "*S-1-1-0" >NUL 2>NUL

                    icacls "%SSH_KEY%" /grant:r "*S-1-5-18:R" "*S-1-5-32-544:R" >NUL
                    if errorlevel 1 exit /b %errorlevel%

                    echo ===== Upload jar to server =====
                    scp -i "%SSH_KEY%" -o StrictHostKeyChecking=accept-new "%BACKEND_LOCAL_JAR%" "%DEPLOY_USER%@%DEPLOY_HOST%:%REMOTE_TMP_JAR%"
                    if errorlevel 1 exit /b %errorlevel%

                    echo ===== Restart remote service =====
                    ssh -i "%SSH_KEY%" -o StrictHostKeyChecking=accept-new "%DEPLOY_USER%@%DEPLOY_HOST%" "sudo mv %REMOTE_TMP_JAR% %REMOTE_APP_JAR% && sudo chown familyapp:familyapp %REMOTE_APP_JAR% && sudo systemctl restart %SERVICE_NAME% && sudo systemctl status %SERVICE_NAME% --no-pager"
                    if errorlevel 1 exit /b %errorlevel%
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'family-repurchase-agent build and deploy success.'
        }

        failure {
            echo 'family-repurchase-agent build or deploy failed.'
        }
    }
}