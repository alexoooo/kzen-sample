package tech.kzen.sample.itch.model;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;


/**
 * An immutable sorted map with structural sharing (a persistent AVL tree): every update returns a new map that
 * shares all untouched subtrees with its predecessor, so a sequence of book states costs O(log n) new nodes per
 * update while each historical state keeps exactly the meaning it had. Plain Java, no dependency, iterable in
 * comparator order as {@link Map.Entry} views.
 *
 * Chosen over a library because the core must stay dependency-free and the book needs only ordered
 * put/remove/iterate; the per-update allocation is measured in HS06 rather than assumed.
 */
public final class PersistentSortedMap<K, V> implements Iterable<Map.Entry<K, V>> {
    private final Comparator<? super K> comparator;
    private final Node<K, V> root;
    private final int size;


    public static <K extends Comparable<? super K>, V> PersistentSortedMap<K, V> empty() {
        return new PersistentSortedMap<>(Comparator.naturalOrder(), null, 0);
    }

    public static <K, V> PersistentSortedMap<K, V> empty(Comparator<? super K> comparator) {
        return new PersistentSortedMap<>(comparator, null, 0);
    }


    private PersistentSortedMap(Comparator<? super K> comparator, Node<K, V> root, int size) {
        this.comparator = comparator;
        this.root = root;
        this.size = size;
    }


    //-----------------------------------------------------------------------------------------------------------------
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public V get(K key) {
        Node<K, V> node = root;
        while (node != null) {
            int c = comparator.compare(key, node.key);
            if (c == 0) {
                return node.value;
            }
            node = c < 0 ? node.left : node.right;
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public PersistentSortedMap<K, V> put(K key, V value) {
        Objects.requireNonNull(value, "value");
        boolean[] added = {false};
        Node<K, V> newRoot = insert(root, key, value, added);
        return new PersistentSortedMap<>(comparator, newRoot, added[0] ? size + 1 : size);
    }

    public PersistentSortedMap<K, V> remove(K key) {
        if (get(key) == null) {
            return this;
        }
        return new PersistentSortedMap<>(comparator, delete(root, key), size - 1);
    }

    /** The smallest key's entry, or null when empty. */
    public Map.Entry<K, V> firstEntry() {
        Node<K, V> node = root;
        if (node == null) {
            return null;
        }
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    /** Up to [limit] entries from the smallest key; the "top of book" when the comparator puts the best price first. */
    public List<Map.Entry<K, V>> first(int limit) {
        List<Map.Entry<K, V>> result = new ArrayList<>(Math.min(limit, size));
        Iterator<Map.Entry<K, V>> iterator = iterator();
        while (result.size() < limit && iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }


    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
        return new InOrder<>(root);
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static final class Node<K, V> extends AbstractMap.SimpleImmutableEntry<K, V> {
        final K key;
        final V value;
        final Node<K, V> left;
        final Node<K, V> right;
        final int height;

        Node(K key, V value, Node<K, V> left, Node<K, V> right) {
            super(key, value);
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.height = 1 + Math.max(height(left), height(right));
        }
    }

    private static int height(Node<?, ?> node) {
        return node == null ? 0 : node.height;
    }

    private static int balance(Node<?, ?> node) {
        return height(node.left) - height(node.right);
    }

    private Node<K, V> insert(Node<K, V> node, K key, V value, boolean[] added) {
        if (node == null) {
            added[0] = true;
            return new Node<>(key, value, null, null);
        }
        int c = comparator.compare(key, node.key);
        if (c == 0) {
            return new Node<>(key, value, node.left, node.right);
        }
        if (c < 0) {
            return rebalance(new Node<>(node.key, node.value, insert(node.left, key, value, added), node.right));
        }
        return rebalance(new Node<>(node.key, node.value, node.left, insert(node.right, key, value, added)));
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        int c = comparator.compare(key, node.key);
        if (c < 0) {
            return rebalance(new Node<>(node.key, node.value, delete(node.left, key), node.right));
        }
        if (c > 0) {
            return rebalance(new Node<>(node.key, node.value, node.left, delete(node.right, key)));
        }
        if (node.left == null) {
            return node.right;
        }
        if (node.right == null) {
            return node.left;
        }
        Node<K, V> successor = node.right;
        while (successor.left != null) {
            successor = successor.left;
        }
        return rebalance(new Node<>(successor.key, successor.value, node.left, delete(node.right, successor.key)));
    }

    private static <K, V> Node<K, V> rebalance(Node<K, V> node) {
        int balance = balance(node);
        if (balance > 1) {
            Node<K, V> left = balance(node.left) < 0 ? rotateLeft(node.left) : node.left;
            return rotateRight(new Node<>(node.key, node.value, left, node.right));
        }
        if (balance < -1) {
            Node<K, V> right = balance(node.right) > 0 ? rotateRight(node.right) : node.right;
            return rotateLeft(new Node<>(node.key, node.value, node.left, right));
        }
        return node;
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> pivot = node.left;
        return new Node<>(pivot.key, pivot.value, pivot.left,
                new Node<>(node.key, node.value, pivot.right, node.right));
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> pivot = node.right;
        return new Node<>(pivot.key, pivot.value,
                new Node<>(node.key, node.value, node.left, pivot.left), pivot.right);
    }


    private static final class InOrder<K, V> implements Iterator<Map.Entry<K, V>> {
        private final List<Node<K, V>> stack = new ArrayList<>();

        InOrder(Node<K, V> root) {
            push(root);
        }

        private void push(Node<K, V> node) {
            while (node != null) {
                stack.add(node);
                node = node.left;
            }
        }

        @Override
        public boolean hasNext() {
            return !stack.isEmpty();
        }

        @Override
        public Map.Entry<K, V> next() {
            if (stack.isEmpty()) {
                throw new NoSuchElementException();
            }
            Node<K, V> node = stack.removeLast();
            push(node.right);
            return node;
        }
    }
}
