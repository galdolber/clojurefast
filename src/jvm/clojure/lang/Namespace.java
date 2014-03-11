/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jan 23, 2008 */

package clojure.lang;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class Namespace extends AReference implements Serializable {
final public Symbol name;
transient final AtomicReference<IPersistentMap> mappings = new AtomicReference<IPersistentMap>();
transient final AtomicReference<IPersistentMap> aliases = new AtomicReference<IPersistentMap>();
transient final AtomicReference<IPersistentMap> refers = new AtomicReference<IPersistentMap>();

final static ConcurrentHashMap<Symbol, Namespace> namespaces = new ConcurrentHashMap<Symbol, Namespace>();

public String toString(){
	return name.toString();
}

Namespace(Symbol name){
	super(name.meta());
	this.name = name;
	mappings.set(RT.DEFAULT_IMPORTS);
	aliases.set(RT.map());
	refers.set(RT.map());
	if (!name.name.equals("clojure.core")) {
	  referNs(RT.CLOJURE_NS, RT.map());
	}
}

public static ISeq all(){
	return RT.seq(namespaces.values());
}

public Symbol getName(){
	return name;
}

public IPersistentMap getMappings(){
	return mappings.get();
}

public Var intern(Symbol sym){
	if(sym.ns != null)
		{
		throw new IllegalArgumentException("Can't intern namespace-qualified symbol");
		}
	IPersistentMap map = getMappings();
	Object o;
	Var v = null;
	while((o = map.valAt(sym)) == null)
		{
		if(v == null)
			v = new Var(this, sym);
		IPersistentMap newMap = map.assoc(sym, v);
		mappings.compareAndSet(map, newMap);
		map = getMappings();
		}
	if(o instanceof Var && ((Var) o).ns == this)
		return (Var) o;

	if(v == null)
		v = new Var(this, sym);

	warnOrFailOnReplace(sym, o, v);


	while(!mappings.compareAndSet(map, map.assoc(sym, v)))
		map = getMappings();

	return v;
}

private void warnOrFailOnReplace(Symbol sym, Object o, Object v){
    if (o instanceof Var)
        {
        Namespace ns = ((Var)o).ns;
        if (ns == this)
            return;
        if (ns != RT.CLOJURE_NS)
            throw new IllegalStateException(sym + " already refers to: " + o + " in namespace: " + name);
        }
	RT.errPrintWriter().println("WARNING: " + sym + " already refers to: " + o + " in namespace: " + name
		+ ", being replaced by: " + v);
}

Object reference(Symbol sym, Object val){
	if(sym.ns != null)
		{
		throw new IllegalArgumentException("Can't intern namespace-qualified symbol");
		}
	IPersistentMap map = getMappings();
	Object o;
	while((o = map.valAt(sym)) == null)
		{
		IPersistentMap newMap = map.assoc(sym, val);
		mappings.compareAndSet(map, newMap);
		map = getMappings();
		}
	if(o == val)
		return o;

	warnOrFailOnReplace(sym, o, val);

	while(!mappings.compareAndSet(map, map.assoc(sym, val)))
		map = getMappings();

	return val;

}

public static boolean areDifferentInstancesOfSameClassName(Class cls1, Class cls2) {
    return (cls1 != cls2) && (cls1.getName().equals(cls2.getName()));
}

Class referenceClass(Symbol sym, Class val){
    if(sym.ns != null)
        {
        throw new IllegalArgumentException("Can't intern namespace-qualified symbol");
        }
    IPersistentMap map = getMappings();
    Class c = (Class) map.valAt(sym);
    while((c == null) || (areDifferentInstancesOfSameClassName(c, val)))
        {
        IPersistentMap newMap = map.assoc(sym, val);
        mappings.compareAndSet(map, newMap);
        map = getMappings();
        c = (Class) map.valAt(sym);
        }
    if(c == val)
        return c;

    throw new IllegalStateException(sym + " already refers to: " + c + " in namespace: " + name);
}

public void unmap(Symbol sym) {
	if(sym.ns != null)
		{
		throw new IllegalArgumentException("Can't unintern namespace-qualified symbol");
		}
	IPersistentMap map = getMappings();
	while(map.containsKey(sym))
		{
		IPersistentMap newMap = map.without(sym);
		mappings.compareAndSet(map, newMap);
		map = getMappings();
		}
}

public Class importClass(Symbol sym, Class c){
	return referenceClass(sym, c);

}

public Class importClass(Class c){
	String n = c.getName();
	return importClass(Symbol.intern(n.substring(n.lastIndexOf('.') + 1)), c);
}

public Var refer(Symbol sym, Var var){
	return (Var) reference(sym, var);
}

static Keyword only = Keyword.intern("only");
static Keyword onlyAndRefer = Keyword.intern("onlyAndRefer");
static Keyword refer = Keyword.intern("refer");
static Keyword exclude = Keyword.intern("exclude");
static Keyword all = Keyword.intern("all");
static Keyword rename = Keyword.intern("rename");

public Namespace referNs(Object ns, IPersistentMap filters) {
  Object refer = filters.valAt(Namespace.refer);
  Object exclude = filters.valAt(Namespace.exclude);
  Object only = filters.valAt(Namespace.only);
  only = only == null ? RT.set() : PersistentHashSet.create(RT.seq(only));
  Object rename = filters.valAt(Namespace.rename);

  IPersistentSet onlyAndRefer = (IPersistentSet) only;
  if (refer != null && refer instanceof Sequential) {
    for (ISeq e = RT.seq(refer); e != null; e = e.next()) {
      onlyAndRefer = (IPersistentSet) onlyAndRefer.cons(e.first());
    }
  }

  filters = filters
      .assoc(Namespace.onlyAndRefer, onlyAndRefer)
      .assoc(
          Namespace.refer,
          refer == null && refer instanceof Sequential ? PersistentHashSet
              .create(RT.seq(refer)) : refer)
      .assoc(
          Namespace.exclude,
          exclude == null ? RT.set() : PersistentHashSet.create(RT
              .seq(exclude))).assoc(Namespace.only, only)
      .assoc(Namespace.rename, rename == null ? RT.map() : rename);
  boolean successful = false;
  while (!successful) {
    IPersistentMap expects = refers.get();
    successful = refers.compareAndSet(expects, expects.assoc(ns, filters));
  }
  return this;
}

public static Namespace findOrCreate(Symbol name){
	Namespace ns = namespaces.get(name);
	if(ns != null)
		return ns;
	Namespace newns = new Namespace(name);
	ns = namespaces.putIfAbsent(name, newns);
	return ns == null ? newns : ns;
}

public static Namespace remove(Symbol name){
	if(name.equals(RT.CLOJURE_NS.name))
		throw new IllegalArgumentException("Cannot remove clojure namespace");
	return namespaces.remove(name);
}

public static Namespace find(Symbol name){
	return namespaces.get(name);
}

public Object getMapping(Symbol name) {
  Object val = mappings.get().valAt(name);
  if (val == null) {
    val = Var.maybeLoadFromClass(this.name.toString(), name.toString());
    if (val == null) {
      val = searchMapping(name);
      if (val != null && val instanceof Var) {
        refer(name, (Var) val);
      }
    }
    return val;
  }
  return val;
}

private Object searchMapping(Symbol name) {
  IPersistentMap m = refers.get();
  for (ISeq s = m.seq(); s != null; s = s.next()) {
    Object o = null;
    Object i = s.first();
    Namespace ns = (Namespace) RT.first(i);
    IPersistentMap filters = (IPersistentMap) RT.second(i);
    Object refer = filters.valAt(Namespace.refer);
    IPersistentSet exclude = (IPersistentSet) filters
        .valAt(Namespace.exclude);
    IPersistentMap rename = (IPersistentMap) filters.valAt(Namespace.rename);
    if (exclude.contains(name)) {
      continue;
    } else if (rename.containsKey(name)) {
      o = ns.getMapping((Symbol) rename.valAt(name));
    } else if (Namespace.all.equals(refer)) {
      o = ns.getMapping(name);
    } else {
      IPersistentSet onlyAndRefer = (IPersistentSet) filters
          .valAt(Namespace.onlyAndRefer);
      if (onlyAndRefer.count() > 0 && !onlyAndRefer.contains(name)) {
        continue;
      }
      o = ns.getMapping(name);
    }
    if (o != null && o instanceof Var) {
      return o;
    }
  }
  return null;
}

public Var findInternedVar(Symbol symbol){
	Object o = getMapping(symbol);
	if(o != null && o instanceof Var && ((Var) o).ns == this)
		return (Var) o;
	return Var.maybeLoadFromClass(name.toString(), symbol.toString());
}


public IPersistentMap getAliases(){
	return aliases.get();
}

public Namespace lookupAlias(Symbol alias){
	IPersistentMap map = getAliases();
	return (Namespace) map.valAt(alias);
}

public void addAlias(Symbol alias, Namespace ns){
	if (alias == null || ns == null)
		throw new NullPointerException("Expecting Symbol + Namespace");
	IPersistentMap map = getAliases();
	while(!map.containsKey(alias))
		{
		IPersistentMap newMap = map.assoc(alias, ns);
		aliases.compareAndSet(map, newMap);
		map = getAliases();
		}
	// you can rebind an alias, but only to the initially-aliased namespace.
	if(!map.valAt(alias).equals(ns))
		throw new IllegalStateException("Alias " + alias + " already exists in namespace "
		                                   + name + ", aliasing " + map.valAt(alias));
}

public void removeAlias(Symbol alias) {
	IPersistentMap map = getAliases();
	while(map.containsKey(alias))
		{
		IPersistentMap newMap = map.without(alias);
		aliases.compareAndSet(map, newMap);
		map = getAliases();
		}
}

private Object readResolve() throws ObjectStreamException {
    // ensures that serialized namespaces are "deserialized" to the
    // namespace in the present runtime
    return findOrCreate(name);
}
}
