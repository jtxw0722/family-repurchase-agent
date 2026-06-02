pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
    }

    environment {
        APP_NAME = 'family-repurchase-agent'
        JAR_NAME = 'family-repurchase-agent-0.4.0.jar'

        LOCAL_JAR = 'target\\family-repurchase-agent-0.4.0.jar'

        REMOTE_TMP_JAR = '/tmp/family-repurchase-agent.jar'
        REMOTE_APP_JAR = '/opt/family-repurchase-agent/app/family-repurchase-agent.jar'
        SERVICE_NAME = 'family-repurchase-agent'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
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

        stage('Deploy') {
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
                    if not exist "%LOCAL_JAR%" (
                        echo LOCAL_JAR not found: %LOCAL_JAR%
                        exit /b 1
                    )

                    echo ===== Fix SSH private key ACL =====
                    icacls "%SSH_KEY%" /inheritance:r >NUL
                    if errorlevel 1 exit /b %errorlevel%

                    icacls "%SSH_KEY%" /remove:g "*S-1-5-32-545" "*S-1-5-11" "*S-1-1-0" >NUL 2>NUL

                    icacls "%SSH_KEY%" /grant:r "*S-1-5-18:R" "*S-1-5-32-544:R" >NUL
                    if errorlevel 1 exit /b %errorlevel%

                    echo ===== Upload jar to server =====
                    scp -i "%SSH_KEY%" -o StrictHostKeyChecking=accept-new "%LOCAL_JAR%" "%DEPLOY_USER%@%DEPLOY_HOST%:%REMOTE_TMP_JAR%"
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