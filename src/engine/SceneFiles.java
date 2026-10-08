package engine;

import engine.objects.Rect;
import math.Vec3;
import org.w3c.dom.*;
import org.xml.sax.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.stream.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;

/** Strict, embedded-only scene XML persistence. V4 stores analytic spheres and deformable polygon meshes. */
public final class SceneFiles {
    public static final long MAX_FILE_BYTES=16L*1024*1024;
    public static final int MAX_ELEMENTS=250_000,MAX_NODES=50_000,MAX_GEOMETRY_ASSETS=16_000,
            MAX_MATERIAL_ASSETS=16_000,MAX_VERTICES=250_000,MAX_TRIANGLES=500_000,MAX_FACE_CORNERS=1_500_000;
    public static final int MAX_XML_DEPTH=32;
    private static final String FORMAT="ray-tracing-engine-scene";
    private SceneFiles(){}

    public static SceneSnapshot load(Path path) throws IOException {
        Objects.requireNonNull(path,"path");
        long size=Files.size(path);if(size>MAX_FILE_BYTES)throw new SceneFormatException("Scene file exceeds "+MAX_FILE_BYTES+" bytes");
        byte[] bytes;
        try(var in=Files.newInputStream(path)){bytes=in.readNBytes((int)MAX_FILE_BYTES+1);}
        if(bytes.length>MAX_FILE_BYTES)throw new SceneFormatException("Scene file exceeds "+MAX_FILE_BYTES+" bytes");
        try {
            String xml;
            try{xml=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException e){throw format("Scene file is not strict UTF-8",e);}
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",Integer.toString(MAX_XML_DEPTH));
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new ErrorHandler(){
                @Override public void warning(SAXParseException e)throws SAXException{throw e;}
                @Override public void error(SAXParseException e)throws SAXException{throw e;}
                @Override public void fatalError(SAXParseException e)throws SAXException{throw e;}
            });
            var document=builder.parse(new InputSource(new StringReader(xml)));var root=document.getDocumentElement();
            if(root==null||!root.getTagName().equals("scene"))throw format("Root element must be <scene>");
            attributes(root,"format","version");
            if(!FORMAT.equals(required(root,"format")))throw format("Unknown scene format");
            String version=required(root,"version");
            if(!Set.of("1","2","3","4").contains(version))
                throw format("Unsupported scene version: "+version);
            if(countElements(root)>MAX_ELEMENTS)throw format("Scene exceeds element limit "+MAX_ELEMENTS);
            var sections=children(root,"geometry-assets","material-assets","nodes");
            var geometrySection=one(sections,"geometry-assets");var materialSection=one(sections,"material-assets");var nodeSection=one(sections,"nodes");
            var geometries=readGeometries(geometrySection,version);var materials=readMaterials(materialSection);var nodes=readNodes(nodeSection);
            try{return new SceneSnapshot(0,0,nodes,geometries,materials);}
            catch(IllegalArgumentException e){throw format("Invalid scene: "+e.getMessage(),e);}
        } catch(SceneFormatException e){throw e;}
        catch(IllegalArgumentException e){throw format("Invalid scene value: "+e.getMessage(),e);}
        catch(ParserConfigurationException|SAXException e){throw format("Invalid scene XML: "+e.getMessage(),e);}
    }

    /** Fully load and validate before atomically replacing the document's current content. */
    public static SceneSnapshot loadInto(Path path,SceneDocument document)throws IOException{return Objects.requireNonNull(document).replace(load(path));}

    public static void save(Path path,SceneSnapshot snapshot) throws IOException {
        Objects.requireNonNull(path,"path");
        Objects.requireNonNull(snapshot,"snapshot");
        validateForSave(snapshot);
        var absolute=path.toAbsolutePath();var parent=absolute.getParent();if(parent==null)throw new IOException("Scene path needs a parent directory");
        Path temporary=Files.createTempFile(parent,absolute.getFileName().toString()+".",".tmp");boolean moved=false;
        try {
            try(var out=Files.newOutputStream(temporary,TRUNCATE_EXISTING,WRITE)){write(out,snapshot);}
            if(Files.size(temporary)>MAX_FILE_BYTES)throw new IOException("Serialized scene exceeds "+MAX_FILE_BYTES+" bytes");
            try(var channel=FileChannel.open(temporary,WRITE)){channel.force(true);}
            load(temporary);
            try{Files.move(temporary,absolute,ATOMIC_MOVE,REPLACE_EXISTING);moved=true;}
            catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic scene replacement is unavailable; original file was preserved",e);}
        } finally {if(!moved)Files.deleteIfExists(temporary);}
    }

    /** Validate persistence bounds without retaining a serialized copy in memory. */
    public static void validateForSave(SceneSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        validateLimits(snapshot);
        write(new BoundedCountingOutputStream(MAX_FILE_BYTES), snapshot);
    }

    private static void validateLimits(SceneSnapshot snapshot) throws IOException {
        if(snapshot.nodes().size()>MAX_NODES)throw new IOException("Scene exceeds node limit "+MAX_NODES);
        if(snapshot.geometryAssets().size()>MAX_GEOMETRY_ASSETS)throw new IOException("Scene exceeds geometry asset limit "+MAX_GEOMETRY_ASSETS);
        if(snapshot.materialAssets().size()>MAX_MATERIAL_ASSETS)throw new IOException("Scene exceeds material asset limit "+MAX_MATERIAL_ASSETS);
        long vertices=0,triangles=0,corners=0;
        for(var a:snapshot.geometryAssets()) {
            if(a.geometry() instanceof PolygonMesh mesh){vertices=checked(vertices,mesh.editableVertices().size(),"vertex");triangles=checked(triangles,mesh.renderPrimitiveCount(),"triangle");for(var face:mesh.faces())corners=checked(corners,face.vertexIds().size(),"face corner");}
        }
        if(vertices>MAX_VERTICES)throw new IOException("Scene exceeds vertex limit "+MAX_VERTICES);
        if(triangles>MAX_TRIANGLES)throw new IOException("Scene exceeds triangle limit "+MAX_TRIANGLES);
        if(corners>MAX_FACE_CORNERS)throw new IOException("Scene exceeds face corner limit "+MAX_FACE_CORNERS);
        long elements=4L+snapshot.geometryAssets().size()+snapshot.materialAssets().size();
        for(var asset:snapshot.geometryAssets()) {
            if(asset.geometry() instanceof PolygonMesh mesh){long assetCorners=0;for(var face:mesh.faces())assetCorners=checked(assetCorners,face.vertexIds().size(),"face corner");elements=checked(elements,2L+mesh.editableVertices().size()+mesh.faces().size()+assetCorners,"XML element");}
        }
        for(var node:snapshot.nodes())elements+=2L+(node.geometry()!=null?1:0)+(node.light()!=null?1:0)+(node.camera()!=null?1:0);
        if(elements>MAX_ELEMENTS)throw new IOException("Scene exceeds element limit "+MAX_ELEMENTS);
    }

    private static void write(OutputStream output,SceneSnapshot snapshot)throws IOException {
        try {
            var w=XMLOutputFactory.newFactory().createXMLStreamWriter(output,"UTF-8");w.writeStartDocument("UTF-8","1.0");w.writeCharacters("\n");
            start(w,"scene","format",FORMAT,"version","4");w.writeCharacters("\n  ");start(w,"geometry-assets");
            for(var asset:snapshot.geometryAssets()){w.writeCharacters("\n    ");writeGeometry(w,asset);}
            w.writeCharacters("\n  ");w.writeEndElement();w.writeCharacters("\n  ");start(w,"material-assets");
            for(var asset:snapshot.materialAssets()){w.writeCharacters("\n    ");writeMaterial(w,asset);}
            w.writeCharacters("\n  ");w.writeEndElement();w.writeCharacters("\n  ");start(w,"nodes");
            for(var node:snapshot.nodes()){w.writeCharacters("\n    ");writeNode(w,node);}
            w.writeCharacters("\n  ");w.writeEndElement();w.writeCharacters("\n");w.writeEndElement();w.writeCharacters("\n");w.writeEndDocument();w.flush();
        } catch(XMLStreamException e){
            if(e.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException("Could not write scene XML",e);
        }
    }
    private static final class BoundedCountingOutputStream extends OutputStream {
        private final long maximumBytes;
        private long bytesWritten;
        private BoundedCountingOutputStream(long maximumBytes) {
            this.maximumBytes=maximumBytes;
        }
        @Override public void write(int value)throws IOException {
            add(1);
        }
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {
            add(length);
        }
        private void add(int length)throws IOException {
            if(length<0||bytesWritten>maximumBytes-length) {
                throw new IOException("Serialized scene exceeds "+maximumBytes+" bytes");
            }
            bytesWritten+=length;
        }
    }
    private static void writeGeometry(XMLStreamWriter w,GeometryAsset a)throws XMLStreamException {
        var base=new String[]{"id",a.id().toString(),"label",a.label()};
        switch(a.geometry()) {
            case AnalyticSphere s->{start(w,"sphere",base);vecAttributes(w,"center",s.center());w.writeAttribute("radius",f(s.radius()));w.writeEndElement();}
            case PolygonMesh mesh->{
                start(w,"polygon-mesh",append(base,"boundary",mesh.closedBoundary()?"closed-solid":"surface","next-vertex-id",Long.toString(mesh.nextVertexId()),"next-face-id",Long.toString(mesh.nextFaceId())));
                w.writeCharacters("\n      ");start(w,"vertices");for(var v:mesh.editableVertices()){w.writeCharacters("\n        ");start(w,"v","id",Long.toString(v.id()),"x",f(v.position().x()),"y",f(v.position().y()),"z",f(v.position().z()));w.writeEndElement();}w.writeCharacters("\n      ");w.writeEndElement();
                w.writeCharacters("\n      ");start(w,"faces");for(var face:mesh.faces()){w.writeCharacters("\n        ");start(w,"face","id",Long.toString(face.id()));for(long vertexId:face.vertexIds()){w.writeCharacters("\n          ");start(w,"vertex","id",Long.toString(vertexId));w.writeEndElement();}w.writeCharacters("\n        ");w.writeEndElement();}w.writeCharacters("\n      ");w.writeEndElement();w.writeCharacters("\n    ");w.writeEndElement();
            }
        }
    }
    private static void writeMaterial(XMLStreamWriter w,MaterialAsset a)throws XMLStreamException {var m=a.material();start(w,"material","id",a.id().toString(),"label",a.label(),"kind",m.kind().name().toLowerCase(Locale.ROOT),"red",f(m.color().x()),"green",f(m.color().y()),"blue",f(m.color().z()),"ior",f(m.ior()),"absorption-red",f(m.absorption().x()),"absorption-green",f(m.absorption().y()),"absorption-blue",f(m.absorption().z()),"roughness",f(m.roughness()),"emission-red",f(m.emission().x()),"emission-green",f(m.emission().y()),"emission-blue",f(m.emission().z()),"scattering",f(m.scattering()),"anisotropy",f(m.anisotropy()));w.writeEndElement();}
    private static void writeNode(XMLStreamWriter w,SceneNode n)throws XMLStreamException {if(n.parentId()==null)start(w,"node","id",n.id().toString(),"label",n.label());else start(w,"node","id",n.id().toString(),"label",n.label(),"parent",n.parentId().toString());w.writeCharacters("\n      ");writeTransform(w,n.localTransform());if(n.geometry()!=null){w.writeCharacters("\n      ");start(w,"geometry","asset",n.geometry().geometryId().toString(),"material",n.geometry().materialId().toString());w.writeEndElement();}if(n.light()!=null){w.writeCharacters("\n      ");start(w,"point-light","red",f(n.light().color().x()),"green",f(n.light().color().y()),"blue",f(n.light().color().z()),"intensity",f(n.light().intensity()));w.writeEndElement();}if(n.camera()!=null){w.writeCharacters("\n      ");writeCamera(w,n.camera().camera());}w.writeCharacters("\n    ");w.writeEndElement();}
    private static void writeTransform(XMLStreamWriter w,Transform t)throws XMLStreamException {start(w,"transform");vecAttributes(w,"position",t.position);vecAttributes(w,"rotation",t.rotation);vecAttributes(w,"scale",t.scale);w.writeEndElement();}
    private static void writeCamera(XMLStreamWriter w,Camera c)throws XMLStreamException {start(w,"camera","projection",c.projection().name().toLowerCase(Locale.ROOT),"mode",c.mode().name().toLowerCase(Locale.ROOT),"focus",f(c.focus()),"aperture",f(c.aperture()),"height",f(c.height()),"remembered-aperture",f(c.rememberedAperture()));vecAttributes(w,"eye",c.eye());vecAttributes(w,"sensor-origin",c.sensor().origin());vecAttributes(w,"sensor-edge1",c.sensor().edge1());vecAttributes(w,"sensor-edge2",c.sensor().edge2());w.writeEndElement();}
    private static void start(XMLStreamWriter w,String name,String...attrs)throws XMLStreamException{w.writeStartElement(name);for(int i=0;i<attrs.length;i+=2)w.writeAttribute(attrs[i],attrs[i+1]);}
    private static void vecAttributes(XMLStreamWriter w,String name,Vec3 v)throws XMLStreamException{w.writeAttribute(name+"-x",f(v.x()));w.writeAttribute(name+"-y",f(v.y()));w.writeAttribute(name+"-z",f(v.z()));}
    private static String[] append(String[] first,String...rest){var value=Arrays.copyOf(first,first.length+rest.length);System.arraycopy(rest,0,value,first.length,rest.length);return value;}
    private static String f(float value){return Float.toString(value);}

    private static List<GeometryAsset> readGeometries(Element section,String version)throws SceneFormatException {
        attributes(section);var elements=children(section,"sphere","rect","box","mesh","editable-mesh","polygon-mesh","external");
        if(elements.size()>MAX_GEOMETRY_ASSETS)throw format("Scene exceeds geometry asset limit "+MAX_GEOMETRY_ASSETS);
        var result=new ArrayList<GeometryAsset>();long vertices=0,triangles=0,corners=0;
        for(var e:elements){
            if(e.getTagName().equals("external"))throw format("External geometry references are unsupported in scene files");
            if(Set.of("3","4").contains(version)
                    && Set.of("rect","box","mesh","editable-mesh").contains(e.getTagName()))
                throw format("Legacy <"+e.getTagName()+"> geometry is unsupported in scene format v"+version);
            var id=geometryId(e);var label=label(e);
            GeometryData data=switch(e.getTagName()){
                case "sphere"->{attributes(e,"id","label","center-x","center-y","center-z","radius");empty(e);yield new AnalyticSphere(vec(e,"center"),number(e,"radius"));}
                case "rect"->{attributes(e,"id","label","origin-x","origin-y","origin-z","edge1-x","edge1-y","edge1-z","edge2-x","edge2-y","edge2-z");empty(e);yield PolygonMesh.parallelogram(vec(e,"origin"),vec(e,"edge1"),vec(e,"edge2"));}
                case "box"->{attributes(e,"id","label");empty(e);yield BoxGeometry.UNIT;}
                case "mesh"->{
                    attributes(e,"id","label","boundary");var groups=children(e,"vertices","triangles");var vs=one(groups,"vertices");var ts=one(groups,"triangles");attributes(vs);attributes(ts);
                    var ve=children(vs,"v");var te=children(ts,"t");vertices=checkedFormat(vertices,ve.size(),"vertex");triangles=checkedFormat(triangles,te.size(),"triangle");
                    corners=checkedFormat(corners,Math.multiplyExact((long)te.size(),3),"face corner");if(vertices>MAX_VERTICES)throw format("Scene exceeds vertex limit "+MAX_VERTICES);if(triangles>MAX_TRIANGLES)throw format("Scene exceeds triangle limit "+MAX_TRIANGLES);if(corners>MAX_FACE_CORNERS)throw format("Scene exceeds face corner limit "+MAX_FACE_CORNERS);
                    var points=new ArrayList<Vec3>();for(var v:ve){attributes(v,"x","y","z");empty(v);points.add(new Vec3(number(v,"x"),number(v,"y"),number(v,"z")));}
                    var ids=new int[te.size()*3];var faces=new long[te.size()];for(int i=0;i<te.size();i++){var t=te.get(i);attributes(t,"a","b","c","face");empty(t);ids[i*3]=integer(t,"a");ids[i*3+1]=integer(t,"b");ids[i*3+2]=integer(t,"c");faces[i]=longNumber(t,"face");}
                    String boundary=required(e,"boundary");yield switch(boundary){case "surface"->PolygonMesh.triangleSurface(points,ids,faces);case "closed-solid"->PolygonMesh.triangleClosedSolid(points,ids,faces);default->throw format("Unknown mesh boundary: "+boundary);};
                }
                case "editable-mesh","polygon-mesh"->{
                    if(e.getTagName().equals("editable-mesh")&&!version.equals("2"))throw format("Editable topology requires scene format v2");
                    if(e.getTagName().equals("polygon-mesh")&&!Set.of("3","4").contains(version))
                        throw format("Polygon topology requires scene format v3 or v4");
                    attributes(e,"id","label","boundary","next-vertex-id","next-face-id");var groups=children(e,"vertices","faces");var vs=one(groups,"vertices");var fs=one(groups,"faces");attributes(vs);attributes(fs);
                    var ve=children(vs,"v");var fe=children(fs,"face");long derived=0;
                    for(var face:fe){attributes(face,"id");var refs=children(face,"vertex");if(refs.size()<3)throw format("Editable face needs at least three vertices");if(refs.size()>PolygonMesh.MAX_FACE_VERTICES)throw format("Editable face exceeds vertex limit "+PolygonMesh.MAX_FACE_VERTICES);corners=checkedFormat(corners,refs.size(),"face corner");derived=checkedFormat(derived,refs.size()-2L,"triangle");}
                    vertices=checkedFormat(vertices,ve.size(),"vertex");triangles=checkedFormat(triangles,derived,"triangle");
                    if(vertices>MAX_VERTICES)throw format("Scene exceeds vertex limit "+MAX_VERTICES);if(triangles>MAX_TRIANGLES)throw format("Scene exceeds triangle limit "+MAX_TRIANGLES);if(corners>MAX_FACE_CORNERS)throw format("Scene exceeds face corner limit "+MAX_FACE_CORNERS);
                    var points=new ArrayList<PolygonMesh.Vertex>(ve.size());for(var v:ve){attributes(v,"id","x","y","z");empty(v);points.add(new PolygonMesh.Vertex(longNumber(v,"id"),new Vec3(number(v,"x"),number(v,"y"),number(v,"z"))));}
                    var faces=new ArrayList<PolygonMesh.Face>(fe.size());for(var face:fe){var refs=children(face,"vertex");var faceVertices=new ArrayList<Long>(refs.size());for(var ref:refs){attributes(ref,"id");empty(ref);faceVertices.add(longNumber(ref,"id"));}faces.add(new PolygonMesh.Face(longNumber(face,"id"),faceVertices));}
                    long nextVertex=longNumber(e,"next-vertex-id"),nextFace=longNumber(e,"next-face-id");
                    String boundary=required(e,"boundary");
                    PolygonMesh mesh=switch(boundary){
                        case "surface"->PolygonMesh.surface(points,faces,nextVertex,nextFace);
                        case "closed-solid"->PolygonMesh.closedSolid(points,faces,nextVertex,nextFace);
                        default->throw format("Unknown editable mesh boundary: "+boundary);
                    };
                    if(!version.equals("4"))mesh.validateLegacyPlanarity();
                    yield mesh;
                }
                default->throw format("Unknown geometry element");
            };
            result.add(new GeometryAsset(id,label,0,data));
        }
        return result;
    }
    private static List<MaterialAsset> readMaterials(Element section)throws SceneFormatException {
        attributes(section);var elements=children(section,"material");if(elements.size()>MAX_MATERIAL_ASSETS)throw format("Scene exceeds material asset limit "+MAX_MATERIAL_ASSETS);var result=new ArrayList<MaterialAsset>();
        for(var e:elements){attributes(e,"id","label","kind","red","green","blue","ior","absorption-red","absorption-green","absorption-blue","roughness","emission-red","emission-green","emission-blue","scattering","anisotropy");empty(e);var id=materialId(e);var label=label(e);Material.Kind kind;try{kind=Material.Kind.valueOf(required(e,"kind").toUpperCase(Locale.ROOT));}catch(IllegalArgumentException x){throw format("Unknown material kind: "+e.getAttribute("kind"));}var m=new Material(id.toString(),new Vec3(number(e,"red"),number(e,"green"),number(e,"blue")),kind,number(e,"ior"),new Vec3(number(e,"absorption-red"),number(e,"absorption-green"),number(e,"absorption-blue")),number(e,"roughness"),new Vec3(number(e,"emission-red"),number(e,"emission-green"),number(e,"emission-blue")),number(e,"scattering"),number(e,"anisotropy"));result.add(new MaterialAsset(id,label,0,m));}
        return result;
    }
    private static List<SceneNode> readNodes(Element section)throws SceneFormatException {
        attributes(section);var elements=children(section,"node");if(elements.size()>MAX_NODES)throw format("Scene exceeds node limit "+MAX_NODES);var result=new ArrayList<SceneNode>();
        for(var e:elements){attributes(e,"id","label","parent");NodeId id=nodeId(e,"id"),parent=e.hasAttribute("parent")?nodeId(e,"parent"):null;var components=children(e,"transform","geometry","point-light","camera");var transformElement=one(components,"transform");attributes(transformElement,"position-x","position-y","position-z","rotation-x","rotation-y","rotation-z","scale-x","scale-y","scale-z");empty(transformElement);var transform=new Transform(vec(transformElement,"position"),vec(transformElement,"rotation"),vec(transformElement,"scale"));GeometryComponent geometry=null;var ge=list(components,"geometry");if(ge.size()>1)throw format("Node has multiple geometry components");if(!ge.isEmpty()){var g=ge.getFirst();attributes(g,"asset","material");empty(g);geometry=new GeometryComponent(geometryId(g,"asset"),materialId(g,"material"));}PointLightComponent light=null;var le=list(components,"point-light");if(le.size()>1)throw format("Node has multiple point lights");if(!le.isEmpty()){var l=le.getFirst();attributes(l,"red","green","blue","intensity");empty(l);light=new PointLightComponent(new Vec3(number(l,"red"),number(l,"green"),number(l,"blue")),number(l,"intensity"));}CameraComponent camera=null;var ce=list(components,"camera");if(ce.size()>1)throw format("Node has multiple cameras");if(!ce.isEmpty())camera=new CameraComponent(readCamera(ce.getFirst()));result.add(new SceneNode(id,label(e),parent,transform,geometry,light,camera));}
        return result;
    }
    private static Camera readCamera(Element e)throws SceneFormatException {
        attributes(e,"projection","mode","focus","aperture","height","remembered-aperture","eye-x","eye-y","eye-z","sensor-origin-x","sensor-origin-y","sensor-origin-z","sensor-edge1-x","sensor-edge1-y","sensor-edge1-z","sensor-edge2-x","sensor-edge2-y","sensor-edge2-z");empty(e);
        try{return new Camera(vec(e,"eye"),new Rect(vec(e,"sensor-origin"),vec(e,"sensor-edge1"),vec(e,"sensor-edge2")),Camera.Projection.valueOf(required(e,"projection").toUpperCase(Locale.ROOT)),Camera.Mode.valueOf(required(e,"mode").toUpperCase(Locale.ROOT)),number(e,"focus"),number(e,"aperture"),number(e,"height"),number(e,"remembered-aperture")).validated();}catch(IllegalArgumentException x){throw format("Invalid camera: "+x.getMessage(),x);}
    }

    private record ElementDepth(Element element,int depth){}
    private static int countElements(Element root)throws SceneFormatException {int count=0;var stack=new ArrayDeque<ElementDepth>();stack.push(new ElementDepth(root,1));while(!stack.isEmpty()){var current=stack.pop();if(current.depth()>MAX_XML_DEPTH)throw format("Scene XML exceeds depth limit "+MAX_XML_DEPTH);if(++count>MAX_ELEMENTS)return count;var children=current.element().getChildNodes();for(int i=0;i<children.getLength();i++)if(children.item(i)instanceof Element e)stack.push(new ElementDepth(e,current.depth()+1));}return count;}
    private static List<Element> children(Element parent,String...allowed)throws SceneFormatException {var names=Set.of(allowed);var result=new ArrayList<Element>();var nodes=parent.getChildNodes();for(int i=0;i<nodes.getLength();i++){var node=nodes.item(i);if(node instanceof Element e){if(!names.contains(e.getTagName()))throw format("Unexpected <"+e.getTagName()+"> in <"+parent.getTagName()+">");result.add(e);}else if(node instanceof Text text&&!text.getData().isBlank())throw format("Unexpected text in <"+parent.getTagName()+">");else if(!(node instanceof Text)&&!(node instanceof Comment))throw format("Unexpected XML node in <"+parent.getTagName()+">");}return result;}
    private static void empty(Element element)throws SceneFormatException {children(element);}
    private static Element one(List<Element> values,String name)throws SceneFormatException {var found=list(values,name);if(found.size()!=1)throw format("Expected exactly one <"+name+">");return found.getFirst();}
    private static List<Element> list(List<Element> values,String name){return values.stream().filter(e->e.getTagName().equals(name)).toList();}
    private static void attributes(Element element,String...allowed)throws SceneFormatException {var names=Set.of(allowed);var attrs=element.getAttributes();for(int i=0;i<attrs.getLength();i++)if(!names.contains(attrs.item(i).getNodeName()))throw format("Unexpected attribute '"+attrs.item(i).getNodeName()+"' on <"+element.getTagName()+">");}
    private static String required(Element e,String name)throws SceneFormatException {if(!e.hasAttribute(name)||e.getAttribute(name).isBlank())throw format("Missing attribute '"+name+"' on <"+e.getTagName()+">");return e.getAttribute(name);}
    private static String label(Element e)throws SceneFormatException {try{return Labels.checked(required(e,"label"));}catch(IllegalArgumentException x){throw format(x.getMessage(),x);}}
    private static float number(Element e,String name)throws SceneFormatException {String text=required(e,name);if(text.length()>64)throw format("Numeric value is too long");try{float value=Float.parseFloat(text);if(!Float.isFinite(value))throw new NumberFormatException();return value;}catch(NumberFormatException x){throw format("Invalid finite float '"+text+"'",x);}}
    private static int integer(Element e,String name)throws SceneFormatException {String text=required(e,name);if(text.length()>16)throw format("Integer value is too long");try{return Integer.parseInt(text);}catch(NumberFormatException x){throw format("Invalid integer '"+text+"'",x);}}
    private static long longNumber(Element e,String name)throws SceneFormatException {String text=required(e,name);if(text.length()>24)throw format("Integer value is too long");try{return Long.parseLong(text);}catch(NumberFormatException x){throw format("Invalid integer '"+text+"'",x);}}
    private static long checked(long value,long add,String kind)throws IOException{try{return Math.addExact(value,add);}catch(ArithmeticException e){throw new IOException("Scene "+kind+" count overflow",e);}}
    private static long checkedFormat(long value,long add,String kind)throws SceneFormatException{try{return Math.addExact(value,add);}catch(ArithmeticException e){throw format("Scene "+kind+" count overflow",e);}}
    private static Vec3 vec(Element e,String name)throws SceneFormatException{return new Vec3(number(e,name+"-x"),number(e,name+"-y"),number(e,name+"-z"));}
    private static NodeId nodeId(Element e,String name)throws SceneFormatException {try{return NodeId.parse(required(e,name));}catch(IllegalArgumentException x){throw format("Invalid node UUID",x);}}
    private static GeometryId geometryId(Element e)throws SceneFormatException{return geometryId(e,"id");}
    private static GeometryId geometryId(Element e,String name)throws SceneFormatException {try{return GeometryId.parse(required(e,name));}catch(IllegalArgumentException x){throw format("Invalid geometry UUID",x);}}
    private static MaterialId materialId(Element e)throws SceneFormatException{return materialId(e,"id");}
    private static MaterialId materialId(Element e,String name)throws SceneFormatException {try{return MaterialId.parse(required(e,name));}catch(IllegalArgumentException x){throw format("Invalid material UUID",x);}}
    private static SceneFormatException format(String message){return new SceneFormatException(message);}
    private static SceneFormatException format(String message,Throwable cause){return new SceneFormatException(message,cause);}
}
