package org.jboss.eapqe.clustering.jmeter.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

public class MiscHelpers {

    private static final Logger LOG = LoggerFactory.getLogger(MiscHelpers.class);

    public static String generateRandomString() {
        return UUID.randomUUID().toString().substring(1, 16);
    }

    /**
     * If the passed address is an IPv6 address, enclose it in brackets so it can be properly used as part of URLs.
     * It it is an IPv4 address, just return the same address string.
     */
    public String formatPossibleIPv6Address(String address, String threadName) {
        Class<? extends InetAddress> clazz = null;
        try {
            clazz = InetAddress.getByName(address).getClass();
        } catch (UnknownHostException e) {
            LOG.error("{}: Error formatting IPv6 address.", threadName, e);
            return address;
        }
        if (clazz == Inet6Address.class) {
            return "[" + address + "]";
        } else {
            return address;
        }
    }

    public void safeCloseEjbClientContext(InitialContext ctx, String threadName) {
        try {
            Context ejbContext = (Context)ctx.lookup("ejb:");
            if ( ejbContext != null ) {
                ejbContext.close();
            }
        } catch (NamingException e) {
            LOG.error("{}: Error closing EJB Client context.", threadName, e);
        }
        try {
            ctx.close();
        } catch (NamingException e) {
            LOG.error("{}: Error closing InitialContext.", threadName, e);
        }
    }

    public String getPropertiesString(Hashtable props) {
        List<String> list = new ArrayList<>();

        for (Object str:props.entrySet()) {
            list.add(str.toString());
        }
        Collections.sort(list);

        StringBuilder builder = new StringBuilder();
        builder.append("Used properties:" + System.getProperty("line.separator"));

        for (String str: list) {
            builder.append(str).append(System.getProperty("line.separator"));
        }

        return builder.toString();
    }

    private Integer getNextPort(String hosts, String ports, int index){
        if (ports==null || ports.split(",").length==0) { return 8080;}
        else if (ports.split(",").length==1) { return Integer.parseInt(ports);}
        else if (index>ports.split(",").length-1) {
            throw new IllegalStateException(String.format("Hosts and Ports do not match in number: HOSTS:%s PORTS:%s",hosts,ports));
        }
        else {
            return Integer.parseInt(ports.split(",")[index]);
        }
    }

    /**
     * JMeter receives parameters like the following:
     * <br>
     *     <code>
     *         -Jhost=127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1 -Jport=8180,8380,8480,8280
     *     </code>
     * This method receives the two strings of comma separated hosts and ports, e.g.:
     * <ul>
     *     <li>hosts: 127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1</li>
     *     <li>ports: </li>
     * </ul>
     * and produces the ejb client connect string, e.g.:
     * <br>
     *     <code>
     *         remote+http://127.0.0.1:8180,remote+http://127.0.0.1:8380,remote+http://127.0.0.1:8480,remote+http://127.0.0.1:8280
     *     </code>
     * @param hosts
     * @param ports
     * @param threadName
     * @return
     */
    public String getUrlOfHttpRemotingConnector(String hosts, String ports, String threadName, String splitRegex){
        StringBuilder result = new StringBuilder();
        for (int i=0; i<hosts.split(splitRegex).length; i++) {
            result.append("remote+http://");
            result.append(formatPossibleIPv6Address(hosts.split(splitRegex)[i],threadName));
            result.append(":");
            result.append(getNextPort(hosts, ports,i));
            if (i<hosts.split(splitRegex).length-1) result.append(",");
        }
        return result.toString();
    }

    public String getUrlOfHttpRemotingConnector(String hosts, String ports, String threadName){
        return getUrlOfHttpRemotingConnector(hosts, ports, threadName, ",");
    }

    private String removeFirstSlash(String somePath) {
        if (somePath==null) return null;
        return somePath.replaceAll("^\\/","");
    }

    private String removeLastSlash(String somePath) {
        if (somePath==null) return null;
        return somePath.replaceAll("\\/$","");
    }

    public CircularList<String> getUrls(String hosts, String ports, String path, String threadName, String splitRegex){
        CircularList<String> urls = new CircularList<>();
        for (int i=0; i<hosts.split(splitRegex).length; i++) {
            StringBuilder url = new StringBuilder();
            url.append("http://");
            url.append(formatPossibleIPv6Address(hosts.split(splitRegex)[i],threadName));
            url.append(":");
            url.append(getNextPort(hosts, ports,i));
            url.append("/");
            url.append(removeFirstSlash(path));
            urls.add(removeLastSlash(url.toString()));
        }
        return urls;
    }

    public CircularList<Node> getNodes(String hosts, String ports, String path, String threadName, String splitRegex){
        CircularList<Node> urls = new CircularList<>();
        for (int i=0; i<hosts.split(splitRegex).length; i++) {
            StringBuilder url = new StringBuilder();
            url.append("http://");
            url.append(formatPossibleIPv6Address(hosts.split(splitRegex)[i],threadName));
            url.append(":");
            url.append(getNextPort(hosts, ports,i));
            url.append("/");
            url.append(removeFirstSlash(path));
            Node node = new Node(removeLastSlash(url.toString()));
            urls.add(node);
        }
        return urls;
    }
}
