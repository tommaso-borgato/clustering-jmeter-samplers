# clustering-jmeter-samplers

## Example usage with JMeter

### HTTP client

```shell
wget https://dlcdn.apache.org//jmeter/binaries/apache-jmeter-5.6.3.zip
unzip -q apache-jmeter-5.6.3.zip

NODE_IP=127.0.0.1
rm jmeter_results-perf.csv jmeter.log
./apache-jmeter-5.6.3/bin/jmeter -n \
-t TestPlanHTTP.jmx \
-Jjmeter.save.saveservice.output_format=csv \
-Jjmeter.save.saveservice.default_delimiter="," \
-Jjmeter.save.saveservice.autoflush=true \
-l jmeter_results-perf.csv \
-Jhost1=$NODE_IP -Jport1=8180 \
-Jhost2=$NODE_IP -Jport2=8280 \
-Jhost3=$NODE_IP -Jport3=8380 \
-Jhost4=$NODE_IP -Jport4=8480 \
-Jpath=/clusterbench/session \
-Jusers=25 -Jrampup=10 -Jremote.prog=0 \
-Jjmeter.save.saveservice.timestamp_format='yyyy/M/dd HH:mm:ss' \
-Lorg.jboss.eapqe.clustering.jmeter=DEBUG \
-Juser.classpath=clustering-jmeter-samplers-jar-with-dependencies-eap8.jar
```

### EJB Client

```shell
export NODE1_IP=...
export NODE2_IP=...
export NODE3_IP=...
export NODE4_IP=...
rm jmeter_results-perf.csv jmeter.log
./apache-jmeter-5.6.3/bin/jmeter -n \
-t TestPlanEJB.jmx \
-Jjmeter.save.saveservice.output_format=csv \
-Jjmeter.save.saveservice.default_delimiter="," \
-Jjmeter.save.saveservice.autoflush=true \
-l jmeter_results-perf.csv \
-Jhost=$NODE1_IP,$NODE2_IP,$NODE3_IP,$NODE4_IP \
-Jport=8080,8080,8080,8080 \
-Jpath=/clusterbench/session \
-Jusername=joe -Jpassword=secret-Passw0rd -Jusers=1000 -Jrampup=60 -Jremote.prog=0 \
-Jjmeter.save.saveservice.timestamp_format='yyyy/M/dd HH:mm:ss' \
-Lorg.jboss.eapqe.clustering.jmeter=DEBUG \
-Juser.classpath=clustering-jmeter-samplers-jar-with-dependencies-eap8.jar
```

## Using an ORACLE Database

### Stating a local instance 

E.g. https://www.oracle.com/database/free/get-started/

```shell
skopeo inspect docker://container-registry.oracle.com/database/free

podman run --rm --name oracle \
-p 1521:1521 -p 5500:5500 \
-e ORACLE_PWD=redhat123 \
container-registry.oracle.com/database/free:latest

# change SYS password
podman exec oracle ./setPassword.sh redhat123

# add EAPQE user and schema (connect and then execute the sql statements)
podman exec -it oracle sqlplus sys/redhat123@FREEPDB1 as sysdba
    CREATE USER "EAPQE" IDENTIFIED BY "redhat123";
    grant create session to "EAPQE";
    alter user "EAPQE" quota unlimited on users;
    grant DBA to EAPQE;
```

Now you can connect to Oracle via JDBC using `jdbc:oracle:thin:@//10.19.96.242:1521/FREEPDB1` user `SYS` password `redhat123`;
Or you can connect directly with SQLPLUS with `podman exec -it oracle sqlplus EAPQE/redhat123@FREEPDB1`;