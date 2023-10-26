package org.jboss.eapqe.clustering.jmeter.util;

import java.util.Objects;

public class Node {
    private String url;
    private boolean isCacheRefreshed = false;
    private boolean isCreateNode = false;
    private boolean isUpdateNode = false;
    private boolean isDeleteNode = false;

    public Node(String url) {
        assert url != null : "node.url cannot be null! ";
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public boolean isCacheRefreshed() {
        return isCacheRefreshed;
    }

    public void setCacheRefreshed(boolean cacheRefreshed) {
        isCacheRefreshed = cacheRefreshed;
    }

    public boolean isCreateNode() {
        return isCreateNode;
    }

    public void setCreateNode(boolean createNode) {
        isCreateNode = createNode;
    }

    public boolean isUpdateNode() {
        return isUpdateNode;
    }

    public void setUpdateNode(boolean updateNode) {
        isUpdateNode = updateNode;
    }

    public boolean isDeleteNode() {
        return isDeleteNode;
    }

    public void setDeleteNode(boolean deleteNode) {
        isDeleteNode = deleteNode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node)) return false;
        Node node = (Node) o;
        return Objects.equals(getUrl(), node.getUrl());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUrl());
    }
}
