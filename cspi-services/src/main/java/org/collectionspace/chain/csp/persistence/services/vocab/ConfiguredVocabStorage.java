package org.collectionspace.chain.csp.persistence.services.vocab;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.collectionspace.chain.csp.persistence.services.connection.ConnectionException;
import org.collectionspace.chain.csp.persistence.services.connection.RequestMethod;
import org.collectionspace.chain.csp.persistence.services.connection.ReturnedDocument;
import org.collectionspace.chain.csp.persistence.services.connection.ReturnedMultipartDocument;
import org.collectionspace.chain.csp.persistence.services.connection.ReturnedURL;
import org.collectionspace.chain.csp.persistence.services.connection.ServicesConnection;
import org.collectionspace.chain.csp.schema.Instance;
import org.collectionspace.chain.csp.schema.Record;
import org.collectionspace.chain.util.xtmpl.InvalidXTmplException;
import org.collectionspace.csp.api.core.CSPRequestCache;
import org.collectionspace.csp.api.persistence.ExistException;
import org.collectionspace.csp.api.persistence.UnderlyingStorageException;
import org.collectionspace.csp.api.persistence.UnimplementedException;
import org.collectionspace.csp.helper.persistence.ContextualisedStorage;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.Node;
import org.json.JSONException;
import org.json.JSONObject;

public class ConfiguredVocabStorage implements ContextualisedStorage {
	private ServicesConnection conn;
	private VocabInstanceCache vocab_cache;
	private URNProcessor urn_processor;
	private Record r;

	public ConfiguredVocabStorage(Record r,ServicesConnection conn) throws InvalidXTmplException, DocumentException {
		this.conn=conn;
		urn_processor=new URNProcessor(r.getURNSyntax());
		Map<String,String> vocabs=new ConcurrentHashMap<String,String>();
		for(Instance n : r.getAllInstances()) {
			vocabs.put(n.getTitleRef(),n.getTitle());
		}
		vocab_cache=new VocabInstanceCache(r,conn,vocabs);		
		this.r=r;
	}

	private Document createEntry(String namespace,String root_tag,String name,String vocab,String refname) throws UnderlyingStorageException, ConnectionException, ExistException {
		Document out=DocumentFactory.getInstance().createDocument();
		Element root=out.addElement("ns2:"+root_tag,namespace);
		Element nametag=root.addElement("displayName");
		nametag.addText(name);
		Element vocabtag=root.addElement(r.getInTag());
		vocabtag.addText(vocab);
		Element refnametag=root.addElement("refName");
		if(refname!=null)
			refnametag.addText(refname);
		if(r.isType("compute-displayname")) {
			Element dnc=root.addElement("displayNameComputed");
			dnc.addText("false");
		}
		System.err.println("createEntry() ::: "+out.asXML());
		return out;
	}

	public String autocreateJSON(CSPRequestCache cache,String filePath,JSONObject jsonObject)
		throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			if(!jsonObject.has("name"))
				throw new UnderlyingStorageException("Missing name argument to data");
			String name=jsonObject.getString("name");
			String vocab=vocab_cache.getVocabularyId(cache,filePath);
			Map<String,Document> body=new HashMap<String,Document>();
			String[] record_path=r.getServicesRecordPath().split(":",2);
			String[] tag_path=record_path[1].split(",",2);
			// First send without refid (don't know csid)
			body.put(record_path[0],createEntry(tag_path[0],tag_path[1],name,vocab,null));
			ReturnedURL out=conn.getMultipartURL(RequestMethod.POST,"/"+r.getServicesURL()+"/"+vocab+"/items",body);
			if(out.getStatus()>299)
				throw new UnderlyingStorageException("Could not create vocabulary status="+out.getStatus());
			// This time with refid
			String refname=urn_processor.constructURN(vocab,out.getURLTail(),name);
			body.put(record_path[0],createEntry(tag_path[0],tag_path[1],name,vocab,refname));
			out=conn.getMultipartURL(RequestMethod.POST,"/"+r.getServicesURL()+"/"+vocab+"/items",body);
			if(out.getStatus()>299)
				throw new UnderlyingStorageException("Could not create vocabulary status="+out.getStatus());			
			cache.setCached(getClass(),new String[]{"namefor",vocab,out.getURLTail()},name);
			cache.setCached(getClass(),new String[]{"reffor",vocab,out.getURLTail()},refname);
			return out.getURLTail();
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Connection exception",e);
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Cannot parse surrounding JSON",e);
		}
	}

	public void createJSON(CSPRequestCache cache, String filePath,JSONObject jsonObject)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		throw new UnimplementedException("Cannot create at named path");
	}

	public void deleteJSON(CSPRequestCache cache, String filePath)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {			
			String vocab=vocab_cache.getVocabularyId(cache,filePath.split("/")[0]);
			int status=conn.getNone(RequestMethod.DELETE,CSIDtoURL(filePath),null);
			if(status>299)
				throw new UnderlyingStorageException("Could not retrieve vocabulary status="+status);
			cache.removeCached(getClass(),new String[]{"namefor",vocab,filePath.split("/")[1]});
			cache.removeCached(getClass(),new String[]{"reffor",vocab,filePath.split("/")[1]});
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Connection exception",e);
		}	
	}

	@SuppressWarnings("unchecked")
	public String[] getPaths(CSPRequestCache cache,String rootPath,JSONObject restrictions)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			List<String> out=new ArrayList<String>();
			String vocab=vocab_cache.getVocabularyId(cache,rootPath);
			String url="/"+r.getServicesURL()+"/"+vocab+"/items";
			String prefix=null;
			if(restrictions!=null && restrictions.has("name"))
				prefix=restrictions.getString("name");
			// XXX pagination support
			url+="?pgSz=10000";
			if(prefix!=null)
				url+="&pt="+URLEncoder.encode(prefix,"UTF8");
			ReturnedDocument data = conn.getXMLDocument(RequestMethod.GET,url,null);
			Document doc=data.getDocument();
			if(doc==null)
				throw new UnderlyingStorageException("Could not retrieve vocabularies");
			String[] path_parts=r.getServicesListPath().split(":",2);
			String[] tag_parts=path_parts[1].split(",",2);
			List<Node> objects=doc.getDocument().selectNodes(tag_parts[1]);
			for(Node object : objects) {
				String name=object.selectSingleNode("displayName").getText();
				String csid=object.selectSingleNode("csid").getText();
				if(prefix==null || name.toLowerCase().contains(prefix.toLowerCase()))
					out.add(csid);
				cache.setCached(getClass(),new String[]{"namefor",vocab,csid},name);
				String refname=urn_processor.constructURN(vocab,csid,name);
				cache.setCached(getClass(),new String[]{"reffor",vocab,csid},refname);
			}
			return out.toArray(new String[0]);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Connection exception",e);
		} catch (UnsupportedEncodingException e) {
			throw new UnderlyingStorageException("UTF-8 not supported!?");
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Error parsing JSON");
		}
	}

	private String CSIDtoURL(String path) throws ExistException, ConnectionException, UnderlyingStorageException {
		String[] paths=path.split("/");		
		return r.getServicesURL()+"/"+paths[0]+"/items/"+paths[1];
	}
	
	public JSONObject retrieveJSON(CSPRequestCache cache, String filePath)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {			
			String vocab=vocab_cache.getVocabularyId(cache,filePath.split("/")[0]);
			String name=(String)cache.getCached(getClass(),new String[]{"namefor",vocab,filePath.split("/")[1]});
			String refid=(String)cache.getCached(getClass(),new String[]{"reffor",vocab,filePath.split("/")[1]});
			if(name==null || refid==null) {			
				// XXX pagination support
				ReturnedMultipartDocument doc=conn.getMultipartXMLDocument(RequestMethod.GET,CSIDtoURL(filePath),null);
				if(doc.getStatus()==404)
					throw new ExistException("Does not exist");
				if(doc.getStatus()>299)
					throw new UnderlyingStorageException("Could not retrieve vocabulary status="+doc.getStatus());
				String[] record_path=r.getServicesRecordPath().split(":",2);
				String[] tag_path=record_path[1].split(",",2);
				Document result=doc.getDocument(record_path[0]);
				name=result.selectSingleNode(tag_path[1]+"/displayName").getText();
				refid=result.selectSingleNode(tag_path[1]+"/refName").getText();
				cache.setCached(getClass(),new String[]{"namefor",vocab,filePath.split("/")[1]},name);
				cache.setCached(getClass(),new String[]{"reffor",vocab,filePath.split("/")[1]},refid);
			}
			JSONObject out=new JSONObject();
			out.put("name",name);
			out.put("csid",filePath.split("/")[1]);
			out.put("refid",refid);
			return out;
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Connection exception",e);
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Cannot generate JSON",e);
		}
	}

	public void updateJSON(CSPRequestCache cache,String filePath,JSONObject jsonObject)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			if(!jsonObject.has("name"))
				throw new UnderlyingStorageException("Missing name argument to data");
			String name=jsonObject.getString("name");
			Map<String,Document> body=new HashMap<String,Document>();
			String[] record_path=r.getServicesRecordPath().split(":",2);
			String[] tag_path=record_path[1].split(",",2);
			String vocab=vocab_cache.getVocabularyId(cache,filePath.split("/")[0]);
			String refname=urn_processor.constructURN(vocab,filePath.split("/")[1],name);			
			body.put(record_path[0],createEntry(tag_path[0],tag_path[1],name,vocab,refname));
			ReturnedMultipartDocument out=conn.getMultipartXMLDocument(RequestMethod.PUT,CSIDtoURL(filePath),body);
			if(out.getStatus()>299)
				throw new UnderlyingStorageException("Could not create vocabulary status="+out.getStatus());
			cache.setCached(getClass(),new String[]{"namefor",vocab,filePath.split("/")[1]},name);
			cache.setCached(getClass(),new String[]{"reffor",vocab,filePath.split("/")[1]},refname);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Connection exception",e);
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Cannot parse surrounding JSON",e);
		}
	}
}
