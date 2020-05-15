#!/usr/bin/env groovy

boolean slaveDisconnected() {
  return currentBuild.rawBuild.getLog(10000).join('') ==~ /.*(ChannelClosedException|KubernetesClientException|ClosedChannelException|FlowInterruptedException).*/
}

// general properties for CI execution
def static NODE_POOL() { return "agents-n1-standard-32-netssd-preempt" }

def static MAVEN_DOCKER_IMAGE() { return "maven:3.6.1-jdk-8-slim" }

ES_TEST_VERSION_POM_PROPERTY = "elasticsearch.test.version"
CAMBPM_LATEST_VERSION_POM_PROPERTY = "camunda.engine.version"

String getCamBpmDockerImage(String camBpmVersion) {
  return "registry.camunda.cloud/cambpm-ee/camunda-bpm-platform-ee:${camBpmVersion}"
}

String basePodSpec() {
  return """
metadata:
  labels:
    agent: optimize-ci-build
spec:
  nodeSelector:
    cloud.google.com/gke-nodepool: agents-n1-standard-32-netssd-preempt
  tolerations:
    - key: "agents-n1-standard-32-netssd-preempt"
      operator: "Exists"
      effect: "NoSchedule"
  volumes:
  - name: cambpm-config
    configMap:
      # Defined in: https://github.com/camunda/infra-core/tree/master/camunda-ci/deployments/optimize
      name: ci-optimize-cambpm-config
  - name: nginx-config
    configMap:
      # Defined in: https://github.com/camunda/infra-core/tree/master/camunda-ci/deployments/optimize
      name: ci-optimize-nginx-proxy-config
  imagePullSecrets:
  - name: registry-camunda-cloud-secret
  initContainers:
    - name: init-sysctl
      image: busybox
      imagePullPolicy: Always
      command: ["sysctl", "-w", "vm.max_map_count=262144"]
      securityContext:
        privileged: true
        capabilities:
          add: ["IPC_LOCK", "SYS_RESOURCE"]
    - name: increase-the-ulimit
      image: busybox
      command: ["sh", "-c", "ulimit -n 65536"]
      securityContext:
        privileged: true
        capabilities:
          add: ["IPC_LOCK", "SYS_RESOURCE"]
  containers:
  - name: maven
    image: ${MAVEN_DOCKER_IMAGE()}
    command: ["cat"]
    tty: true
    env:
      - name: LIMITS_CPU
        value: 3
      - name: TZ
        value: Europe/Berlin
    resources:
      limits:
        cpu: 6
        memory: 4Gi
      requests:
        cpu: 6
        memory: 4Gi
    """
}

String camBpmContainerSpec(String camBpmVersion) {
  String camBpmDockerImage = getCamBpmDockerImage(camBpmVersion)
  return """
  - name: cambpm
    image: ${camBpmDockerImage}
    tty: true
    env:
      - name: JAVA_OPTS
        value: "-Xms2g -Xmx2g -XX:MaxMetaspaceSize=256m"
      - name: TZ
        value: Europe/Berlin
    resources:
      limits:
        cpu: 4
        memory: 3Gi
      requests:
        cpu: 4
        memory: 3Gi
    volumeMounts:
    - name: cambpm-config
      mountPath: /camunda/conf/tomcat-users.xml
      subPath: tomcat-users.xml
    - name: cambpm-config
      mountPath: /camunda/webapps/manager/META-INF/context.xml
      subPath: context.xml
    """
}

String elasticSearchContainerSpec(def esVersion) {
  String httpPort = "9203"
  return """
  - name: elasticsearch-${httpPort}
    image: docker.elastic.co/elasticsearch/elasticsearch:${esVersion}
    securityContext:
      privileged: true
      capabilities:
        add: ["IPC_LOCK", "SYS_RESOURCE"]
    resources:
      limits:
        cpu: 5
        memory: 4Gi
      requests:
        cpu: 5
        memory: 4Gi
    env:
      - name: ES_NODE_NAME
        valueFrom:
          fieldRef:
            fieldPath: metadata.name
      - name: ES_JAVA_OPTS
        value: "-Xms2g -Xmx2g"
      - name: bootstrap.memory_lock
        value: true
      - name: discovery.type
        value: single-node
      - name: http.port
        value: ${httpPort}
      - name: cluster.name
        value: elasticsearch
   """
}

String nginxContainerSpec(boolean ssl = false, boolean basicAuth = false, int frontendPort,
                          int forwardPort = 9203, String forwardHost = "localhost") {
  String basicAuthConfig = (basicAuth) ? """
      - name: HTPASSWD
        value: elastic:\$apr1\$rAPWdsDW\$/7taaK2oH4AwXgLTbYSpa0                                                         
        """ : ""
  String imageName = "beevelop/nginx-basic-auth:latest"
  String sslConfigName = (basicAuth) ? "ssl-basic-auth.cfg" : "ssl-auth.cfg"
  String sslConfig = (ssl) ? """
    volumeMounts:
    - name: nginx-config
      mountPath: /opt/auth.conf
      subPath: ${sslConfigName}
    - name: nginx-config
      mountPath: /etc/nginx/certs/optimize.crt
      subPath: optimize.crt
    - name: nginx-config
      mountPath: /etc/nginx/certs/optimize.key
      subPath: optimize.key
    """ : ""
  return """
  - name: nginx-${frontendPort}
    image: ${imageName}
    resources:
      limits:
        cpu: 250m
        memory: 256Mi
      requests:
        cpu: 250m
        memory: 256Mi
    env:
      - name: PORT
        value: ${frontendPort}
      - name: FORWARD_HOST
        value: ${forwardHost}
      - name: FORWARD_PORT
        value: ${forwardPort}
   """ + basicAuthConfig + sslConfig
}

static String mavenAgent() {
  return """
metadata:
  labels:
    agent: optimize-ci-build
spec:
  nodeSelector:
    cloud.google.com/gke-nodepool: ${NODE_POOL()}
  tolerations:
    - key: "${NODE_POOL()}"
      operator: "Exists"
      effect: "NoSchedule"
  containers:
  - name: maven
    image: maven:3.6.1-jdk-8-slim
    command: ["cat"]
    tty: true
    env:
      - name: LIMITS_CPU
        valueFrom:
          resourceFieldRef:
            resource: limits.cpu
      - name: TZ
        value: Europe/Berlin
    resources:
      limits:
        cpu: 1
        memory: 512Mi
      requests:
        cpu: 1
        memory: 512Mi
"""
}

String securedEsTestPodSpec(def esVersion, def camBpmVersion) {
  def nginxConfigBasicAuth = nginxContainerSpec(false, true, 80)
  def nginxConfigSslBasicAuth = nginxContainerSpec(true, true, 9200)
  def nginxConfigSsl = nginxContainerSpec(true, false, 9201)
  def esConfig = elasticSearchContainerSpec(esVersion)
  return basePodSpec() + camBpmContainerSpec(camBpmVersion) + nginxConfigBasicAuth + nginxConfigSslBasicAuth + nginxConfigSsl + esConfig
}

pipeline {
  agent none
  environment {
    NEXUS = credentials("camunda-nexus")
  }

  options {
    buildDiscarder(logRotator(numToKeepStr: '50'))
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
  }

  stages {
    stage("Prepare") {
      agent {
        kubernetes {
          cloud 'optimize-ci'
          label "optimize-ci-build-${env.JOB_BASE_NAME}-${env.BUILD_ID}"
          defaultContainer 'jnlp'
          yaml mavenAgent()
        }
      }
      steps {
        cloneGitRepo()
        script {
          def mavenProps = readMavenPom().getProperties()
          env.ES_VERSION = mavenProps.getProperty(ES_TEST_VERSION_POM_PROPERTY)
          env.CAMBPM_VERSION = mavenProps.getProperty(CAMBPM_LATEST_VERSION_POM_PROPERTY)
        }
      }
    }
    stage('Security') {
      agent {
        kubernetes {
          cloud 'optimize-ci'
          label "optimize-ci-build-it-security_${env.JOB_BASE_NAME.replaceAll("%2F", "-").replaceAll("\\.", "-").take(10)}-${env.BUILD_ID}"
          defaultContainer 'jnlp'
          yaml securedEsTestPodSpec(env.ES_VERSION, env.CAMBPM_VERSION)
        }
      }
      steps {
        securityTestSteps()
      }
      post {
        always {
          junit testResults: 'backend/target/failsafe-reports/**/*.xml', allowEmptyResults: true, keepLongStdio: true
        }
      }
    }
  }
  post{
    always {
      // Retrigger the build if the slave disconnected
      script {
          if (slaveDisconnected()) {
           build job: currentBuild.projectName, propagate: false, quietPeriod: 60, wait: false
        }
      }
    }
  }
}

void securityTestSteps() {
  cloneGitRepo()
  container('maven') {
    runMaven("install -Dskip.docker -Dskip.fe.build -DskipTests -pl qa/connect-to-secured-es-tests -am")
    runMaven("verify -Dskip.docker -Dskip.fe.build -pl qa/connect-to-secured-es-tests -Psecured-es-it")
  }
}

private void cloneGitRepo() {
  git url: 'git@github.com:camunda/camunda-optimize',
          branch: "${params.BRANCH}",
          credentialsId: 'camunda-jenkins-github-ssh',
          poll: false
}

void runMaven(String cmd) {
  configFileProvider([configFile(fileId: 'maven-nexus-settings-local-repo', variable: 'MAVEN_SETTINGS_XML')]) {
    sh("mvn ${cmd} -s \$MAVEN_SETTINGS_XML -B --fail-at-end -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn")
  }
}
