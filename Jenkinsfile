#!/usr/bin/env groovy

/* Only keep the 10 most recent builds. */
properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10')),
    pipelineTriggers([[$class:"SCMTrigger", scmpoll_spec:"H/10 * * * *"]]),
])

def isPullRequest = !!(env.CHANGE_ID)
def pushToDocker = infra.isTrusted()
String shortCommit = ''

node('docker') {
    /* Make sure we're always starting with a fresh workspace */
    deleteDir()

    stage('Checkout') {
        checkout scm
        sh 'git rev-parse HEAD > GIT_COMMIT'
        shortCommit = readFile('GIT_COMMIT').take(6)

        dir('deploy/plugin-site') {
            def branch = env.BRANCH_NAME != 'master' ? 'develop' : 'master'
            echo 'Cloning the latest front-end site for baking our container'
            git url: 'https://github.com/jenkins-infra/plugin-site.git', branch: branch
            sh 'git rev-parse HEAD > GIT_COMMIT'
        }
    }

    timestamps {
        stage('Generate Plugin Data') {
            docker.image('maven').inside {
              sh 'mvn -PgeneratePluginData'
            }
        }

        /*
         * Running everything within an nginx container to provide the
         * DATA_FILE_URL necessary for the build and execution of the docker
         * container
         */
        docker.image('nginx:alpine').withRun('-v $PWD/target:/usr/share/nginx/html') { c ->

            /*
             * Building our war file inside a Maven container which links to
             * the nginx container for accessing the DATA_FILE_URL
             */
            stage('Build') {
                docker.image('maven').inside("--link ${c.id}:nginx") {
                    withEnv([
                        'DATA_FILE_URL=http://nginx/plugins.json.gzip',
                    ]) {
                        sh 'mvn -B -Dmaven.test.failure.ignore verify'
                        /* Copy our war file into the deploy directory for easy
                         * COPYing into our container
                         */
                        sh 'cp target/*.war deploy'
                    }
                }

                /** archive all our artifacts for reporting later */
                junit 'target/surefire-reports/**/*.xml'
            }

            /*
             * Build our application container with some extra parameters to
             * make sure it doesn't leave temporary containers behind on the
             * agent
             */
            def container
            stage('Containerize') {
                container = docker.build("jenkinsciinfra/plugin-site:${env.BUILD_ID}-${shortCommit}",
                                        '--no-cache --rm deploy')
                if (pushToDocker) {
                    echo "Pushing container jenkinsciinfra/plugin-site:${env.BUILD_ID}-${shortCommit}"
                    container.push()
                }
            }

            /*
             * Spin up our built container and make sure we can execute API
             * calls against it before calling it successful
             */
            stage('Verify Container') {
                container.withRun("--link ${c.id}:nginx -e DATA_FILE_URL=http://nginx/plugins.json.gzip") { api ->
                    /* Re-using the `maven` image because it happens to have a
                     * proper wget installed already inside of it
                     */
                    docker.image('maven').inside("--link ${api.id}:api") {
                        sh 'wget --debug -O /dev/null --retry-connrefused --timeout 120 --tries=5 http://api:8080/versions'
                    }
                }
            }

            stage('Tag container as latest') {
                if (pushToDocker) {
                    echo "Tagging jenkinsciinfra/plugin-site:${env.BUILD_ID}-${shortCommit} as latest"
                    container.push('latest')
                }
            }
        }

        stage('Archive Artifacts') {
            archiveArtifacts artifacts: 'target/*.war, target/*.json.gzip', fingerprint: true
        }
    }
}
