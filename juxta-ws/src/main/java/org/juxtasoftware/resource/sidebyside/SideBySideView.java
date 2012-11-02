package org.juxtasoftware.resource.sidebyside;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.output.FileWriterWithEncoding;
import org.apache.commons.lang.StringEscapeUtils;
import org.juxtasoftware.Constants;
import org.juxtasoftware.dao.AlignmentDao;
import org.juxtasoftware.dao.CacheDao;
import org.juxtasoftware.dao.ComparisonSetDao;
import org.juxtasoftware.dao.WitnessDao;
import org.juxtasoftware.model.Alignment;
import org.juxtasoftware.model.Alignment.AlignedAnnotation;
import org.juxtasoftware.model.AlignmentConstraint;
import org.juxtasoftware.model.ComparisonSet;
import org.juxtasoftware.model.QNameFilter;
import org.juxtasoftware.model.Witness;
import org.juxtasoftware.resource.BaseResource;
import org.juxtasoftware.util.BackgroundTask;
import org.juxtasoftware.util.BackgroundTaskCanceledException;
import org.juxtasoftware.util.BackgroundTaskStatus;
import org.juxtasoftware.util.QNameFilters;
import org.juxtasoftware.util.TaskManager;
import org.juxtasoftware.util.ftl.FileDirective;
import org.juxtasoftware.util.ftl.FileDirectiveListener;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import eu.interedition.text.Range;
import eu.interedition.text.rdbms.RelationalText;

/**
 * Class used to render the side by side view
 * @author loufoster
 *
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class SideBySideView implements FileDirectiveListener  {
    
    @Autowired private ComparisonSetDao setDao;
    @Autowired private WitnessDao witnessDao;
    @Autowired private QNameFilters filters;
    @Autowired private AlignmentDao alignmentDao;
    @Autowired private CacheDao cacheDao;
    @Autowired private ApplicationContext context;
    @Autowired private TaskManager taskManager;
    @Autowired private Integer averageAlignmentSize;

    protected static final Logger LOG = LoggerFactory.getLogger( Constants.WS_LOGGER_NAME );
    private static final int MEGABYTE = (1024*1024);
    
    private BaseResource parent;
    private List<WitnessInfo> witnessDetails = new ArrayList<SideBySideView.WitnessInfo>(2);
    
    public Representation toHtml( final BaseResource parent, final ComparisonSet set) throws IOException {
        this.parent = parent;
                
        if (parent.getQuery().getValuesMap().containsKey("refresh") ) {
            this.cacheDao.deleteSideBySide(set.getId());
        }
        
        // ensure that the document pair is specified as a param
        if ( parent.getQuery().getValuesMap().containsKey("docs") == false ) {
            parent.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return parent.toTextRepresentation("Missing required docs param");
        } 
        
        // extract the pair of documents for the comparison
        String docs = parent.getQuery().getValues("docs");
        String docsList[] = docs.split(",");
        Long witnessIds[] = new Long[2];
        if ( docsList.length == 2) {
            try {
                witnessIds[0] = Long.parseLong(docsList[0]);
                if ( docsList[1].equals("*") ) {
                    Set<Witness> witnesses = this.setDao.getWitnesses(set);
                    for ( Witness w : witnesses ) {
                        if ( w.getId().equals(witnessIds[0]) == false ) {
                            witnessIds[1] = w.getId();
                            break;
                        }
                    }
                } else {
                    witnessIds[1] = Long.parseLong(docsList[1]);
                }
            } catch ( NumberFormatException e) {
                parent.getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
                return parent.toTextRepresentation("Invalid witness id");
            }
        } else {
            parent.getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return parent.toTextRepresentation("Malformed docs param");
        }
    
        // Grab it from cache if possible
        if ( this.cacheDao.sideBySideExists(set.getId(), witnessIds[0], witnessIds[1]) == true) {
            LOG.info("Pulling side-by-side view from cache");
            Reader sbsReader = this.cacheDao.getSideBySide(set.getId(), witnessIds[0], witnessIds[1]);
            return parent.toHtmlRepresentation(sbsReader);
        }
        
        if ( willOverrunMemory( set, witnessIds[0], witnessIds[1]) ) {
            this.parent.setStatus(Status.SERVER_ERROR_INSUFFICIENT_STORAGE);
            return this.parent.toTextRepresentation(
                "The server has insufficent resources to generate this visualization." +
                "\nTry again later. If this fails, try breaking large witnesses up into smaller segments.");
        }
        
        // get witnesses for each ID and initialize the changes map
        for ( int i=0; i<witnessIds.length; i++ ) {
            Witness w = this.witnessDao.find(witnessIds[i]);
            if ( w == null ) {
                parent.getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
                return parent.toTextRepresentation("Invalid witness ID "+witnessIds[i]);
            }
            this.witnessDetails.add( new WitnessInfo(w) );
        }
        
        // render side by side asynchronously
        final String taskId =  generateTaskId(set.getId(), witnessIds[0], witnessIds[1]);
        if ( this.taskManager.exists(taskId) == false ) {
            SideBySideTask task = new SideBySideTask(taskId, set);
            this.taskManager.submit(task);
        } 
        return this.parent.toHtmlRepresentation( new StringReader("RENDERING "+taskId));
    }
    
    private String generateTaskId( final Long setId, final Long leftId, final Long rightId) {
        final int prime = 31;
        int result = 1;
        result = prime * result + setId.hashCode();
        result = prime * result + leftId.hashCode();
        result = prime * result + rightId.hashCode();
        return "sidebyside-"+result;
    }

    private void render(final ComparisonSet set ) throws IOException {
        // special case! Only attempt to get and connect
        // differences if the comparands are different.
        Long leftWitId = this.witnessDetails.get(0).getId();
        Long rightWitId = this.witnessDetails.get(1).getId();
        if ( leftWitId.equals(rightWitId ) == false ) {
            // generate the change lists for each witness and
            // update the changes map with this data
            generateWitnessChangeLists(set);
    
            // find connections between changes in each witness
            connectChanges();
            
            // find any marked transpositions, connect them and
            // add them to the witness info
            connectTranspositions(set);
        }
        
        // render each witness text with changes injected
        for ( WitnessInfo info : this.witnessDetails ) {
            renderDocument( info );
        }
        
        // get all of the witnesses in this set. It will be used
        // by the front end to present the user with a list
        // of witnesses when chaning comparands
        Set<Witness> witnesses = this.setDao.getWitnesses(set);
        
        // stuff this info into a map for freemarker
        FileDirective fileDirective = new FileDirective();
        fileDirective.setListener( this );
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("setId", set.getId());
        map.put("setName", set.getName());
        map.put("page", "set");
        map.put("title", "Juxta Side By Side View: "+set.getName());
        map.put("fileReader", fileDirective );  
        map.put("witnessDetails", this.witnessDetails); 
        map.put("witnesses", witnesses);
        
        // IMPORTANT: the last FALSE param tells the base not to GZIP the results.
        Representation sbsFtl =  this.parent.toHtmlRepresentation("side_by_side.ftl", map, true, false);
        
        // Stream data into cache DB (this invalidates the reader), then stream it back out of
        // the db, back to the client. 
        // NOTE: this can be a big file. Be sure to update the mysql config to handle large posts.
        // This is usually in /etc/my.cnf. The setting to add is: max_allowed_packet=8M (or whaterver size)
        this.cacheDao.cacheSideBySide(set.getId(), leftWitId, rightWitId, sbsFtl.getReader());
    }
    
    private boolean willOverrunMemory(ComparisonSet set, Long wit1, Long wit2) {
        
        QNameFilter changesFilter = this.filters.getDifferencesFilter();
        AlignmentConstraint constraints = new AlignmentConstraint(set);
        constraints.addWitnessIdFilter( wit1 );
        constraints.addWitnessIdFilter( wit2 );
        constraints.setFilter(changesFilter);
        
        // Get the number of annotations that will be returned and do a rough calcuation
        // to see if generating this visuzlization will exhaust available memory
        final Long count = this.alignmentDao.count(constraints);
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        final long estimatedByteUsage = count*this.averageAlignmentSize;
        LOG.info("["+ estimatedByteUsage+"] ESTIMATED USAGE");
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage usage = memoryBean.getHeapMemoryUsage();
        long freeMem = usage.getMax() -usage.getUsed();
        LOG.info("["+ freeMem  +"] ESTIMATED FREE");
        final long memoryPad = MEGABYTE*5; // 5M memory pad
        return ( (estimatedByteUsage+memoryPad) > freeMem );
    }

    @Override
    public void fileReadComplete(File file) {
        // once the file has been rendered to the template
        // it is no longer needed and can be deleted
        file.delete();
    }
    
    void connectTranspositions(ComparisonSet set) {
        // grab any transpositions that have been marked
        AlignmentConstraint constraint = new AlignmentConstraint( set );
        constraint.setFilter( this.filters.getTranspositionsFilter() );
        constraint.addWitnessIdFilter( this.witnessDetails.get(0).getId());
        constraint.addWitnessIdFilter( this.witnessDetails.get(1).getId());
        List<Alignment> transpositions = this.alignmentDao.list(constraint);
        
        for ( Alignment align :  transpositions ) {
            Transposition prior = null;
            for ( AlignedAnnotation a : align.getAnnotations() ) {
                WitnessInfo witnessInfo = getWitnessInfo(a.getWitnessId());
                Transposition t = new Transposition(a.getRange());
                witnessInfo.addTransposition(t);   
                
                // once we have 2, connect them
                if ( prior != null ) {
                    prior.connectedToId = t.id;
                    t.connectedToId = prior.id;
                    
                }
                prior = t;
            }
        }
        
        // sort in range order
        Collections.sort(  this.witnessDetails.get(0).getTranspositions() );
        Collections.sort(  this.witnessDetails.get(1).getTranspositions() );
        
    }
    
    void connectChanges() {
        LOG.info("Make connections");
        // walk through each set of changes for the comparands.
        // changes are considered connected if they share an
        // alignment id
        for ( Change change : this.witnessDetails.get(0).changes ) {
            for ( Change otherChange : this.witnessDetails.get(1).changes ) {
                if ( change.isConnected(otherChange)) {
                    change.connectedToId = otherChange.id;
                    otherChange.connectedToId = change.id;
                    break;
                }
            }
        }
        
        // scan thru the list starting with the OPPOSITE witness and 
        // look for un-connected changes. This may happen if a change was
        // merged in one witness but not the other.
        for ( Change change : this.witnessDetails.get(1).changes ) {
            if ( change.connectedToId == null) {
                for ( Change otherChange : this.witnessDetails.get(0).changes ) {
                    if ( change.isConnected(otherChange)) {
                        change.connectedToId = otherChange.id;
                        otherChange.connectedToId = change.id;
                        break;
                    }
                }
            }
        }
    }
    
    private void generateWitnessChangeLists(final ComparisonSet set) {
        // get all of the alignments that involve one of the 
        // witnesses in this comparison. Split the changes into
        // separate lists for each
        LOG.info("Get diffs...");
        QNameFilter changesFilter = this.filters.getDifferencesFilter();
        AlignmentConstraint constraints = new AlignmentConstraint(set);
        constraints.addWitnessIdFilter( this.witnessDetails.get(0).getId() );
        constraints.addWitnessIdFilter( this.witnessDetails.get(1).getId() );
        constraints.setFilter(changesFilter);
        for ( Alignment align :  this.alignmentDao.list(constraints) ) {
            for ( AlignedAnnotation a : align.getAnnotations() ) {
                WitnessInfo witnessInfo = getWitnessInfo(a.getWitnessId());
                Change newChange = new Change(align, a.getRange(), align.getGroup());
                witnessInfo.addChange( newChange );  
            }
        }
        
        // sort each change set in ascending range order and merge adjacent changes
        LOG.info("Sort and merge diffs....");
        for ( WitnessInfo info : this.witnessDetails ) {
            Collections.sort( info.getChanges() );
            Change prior = null;
            for ( Iterator<Change> itr =  info.getChanges().iterator(); itr.hasNext();  ) {
                Change change = itr.next();
                if (prior != null) {
                    if ( change.hasMatchingGroup( prior) ) {
                        prior.merge(change);
                        itr.remove();
                        continue;
                    }
                }
                prior = change;
            }
        }
    }
    
    private WitnessInfo getWitnessInfo( Long id ) {
        for ( WitnessInfo wi : this.witnessDetails ) {
            if ( wi.witness.getId().equals( id ) ) {
                return wi;
            }
        }
        return null;
    }
    
    private void renderDocument(WitnessInfo info ) throws IOException {
                
        // create content injectors
        final DiffInjector diffInjector = this.context.getBean(DiffInjector.class);
        diffInjector.initialize(  info.getChanges() );
        final TranspositionInjector moveInjector = this.context.getBean(TranspositionInjector.class);
        moveInjector.initialize(info.getTranspositions());
        
        BufferedWriter writer = new BufferedWriter( new FileWriterWithEncoding(info.file, "UTF-8") );
        Reader reader = this.witnessDao.getContentStream(info.witness);
        StringBuilder line = new StringBuilder();
        boolean done = false;
        int pos = 0;
        
        long lastMoveStart = -1;
        long lastDiffStart = -1;
        while ( done == false ) {
            int data = reader.read();
            if ( data == -1 ) {
                done = true;
            } 
            
            // as long as any injectors are ready, keep going
            while ( diffInjector.hasContent(pos) || moveInjector.hasContent(pos)  ) { 
                
                // dump in start tags and track their positions. 
                // this info will be used to detect overlaps and fix them
                if ( moveInjector.injectContentStart(line, pos) ) {
                    lastMoveStart = pos;
                }
                if ( diffInjector.injectContentStart(line, pos) ) {
                    lastDiffStart = pos;
                }
    
                // now see if any of this injected data needs to be closed
                // and handle any overlapping heirarchies
                if ( diffInjector.injectContentEnd(line, pos) == true ) {
                    if ( lastMoveStart != -1 && lastDiffStart < lastMoveStart) {
                        moveInjector.restartContent(line);
                    }
                    lastDiffStart = -1;
                }
                if ( moveInjector.injectContentEnd(line, pos) == true ) {
                    if ( lastDiffStart != -1 && lastMoveStart < lastDiffStart ) {
                        diffInjector.restartContent(line);
                    }
                    lastMoveStart = -1;
                }
            }

            // once a newline or EOF is reached, write it to the data file
            if ( data == '\n' || data == -1 ) {
                line.append("<br/>");
                writer.write(line.toString());
                writer.newLine();
                line = new StringBuilder();
            } else {
                // escape the text before appending it to the output stream
                line.append( StringEscapeUtils.escapeHtml( Character.toString((char)data) ) );
            }
            pos++;
        }
        
        // close up the file
        writer.close();
        
    }
    
    /**
     * A collection of simplified side-by-side information for a witness
     */
    public static class WitnessInfo {
        final Witness witness;
        File file;
        List<Change> changes;
        List<Transposition> transpositions;
        
        public WitnessInfo( Witness witness ) throws IOException {
            this.witness = witness;
            this.changes = new ArrayList<Change>();
            this.transpositions = new ArrayList<SideBySideView.Transposition>();
            this.file = File.createTempFile("sbs_"+witness.getId(), "dat");
            this.file.deleteOnExit();
        }
        
        public Long getId() {
            return this.witness.getId();
        }
        
        public Long getTextId() {
            return ((RelationalText)this.witness.getText()).getId();
        }
        
        public String getName() {
            return this.witness.getName();
        }
        
        public File getFile() {
            return this.file;
        }
        
        public void addChange( Change c ) {
            this.changes.add(c);
        }
        
        public List<Change> getChanges() {
            return this.changes;
        }
        
        public void addTransposition( Transposition t ) {
            this.transpositions.add(t);
        }
        
        public List<Transposition> getTranspositions() {
            return this.transpositions;
        }
        
        @Override 
        public String toString() {
            return this.witness.getName();
        }
    }
    
    /**
     * Class to track transpositions
     */
    static class Transposition implements Comparable<Transposition> {
        final Long id;
        Long connectedToId;
        Range range;
        static long idGen = 0;
        
        public Transposition(Range witnessRange) {
            this.id = Change.idGen++;
            this.range = witnessRange;
        }
        
        public Range getRange() {
            return this.range;
        }
        @Override
        public int compareTo(Transposition that) {
            if ( this.range.getStart() < that.range.getStart() ) {
                return -1;
            } else if ( this.range.getStart() > that.range.getStart() ) {
                return 1;
            } else {
                if ( this.range.getEnd() < that.range.getEnd() ) {
                    return -1;
                } else if ( this.range.getEnd() > that.range.getEnd() ) {
                    return 1;
                } 
            }
            return 0;
        }
    }

    /**
     * Simplified class to track change by id range and type
     */
    static class Change implements Comparable<Change> {
        List<Long> alignIdList = new ArrayList<Long>();
        final Long id;
        final int group;
        Long connectedToId;
        String type;
        Range range;
        static long idGen = 0;
        
        public Change( Alignment align, Range witnessRange, int group) {
            this.id = Change.idGen++;
            this.group = group;
            this.alignIdList.add( align.getId() );
            this.range = witnessRange;
            this.type = "Change";
            if ( align.getName().equals(Constants.ADD_DEL_NAME) ) {
                if ( range.length() == 0 ) {
                    this.type = "Deleted";
                } else {
                    this.type = "Inserted";
                }
            }
        }
        
        public boolean hasMatchingGroup(Change prior) {
            if ( getGroup() == 0 || prior.getGroup() == 0 ) {
                return false;
            } else {
                return (getGroup() == prior.getGroup());
            }
        }

        public int getGroup() {
            return this.group;
        }
        
        public Range getRange() {
            return this.range;
        }
        public boolean isConnected( Change other ) {
            for ( Long id : this.alignIdList ) {
                for ( Long otherId : other.alignIdList ) {
                    if ( id.equals(otherId)) {
                        return true;
                    }
                }
            }
            return false;
        }
        
        public void merge(Change other ) {
            this.alignIdList.addAll( other.alignIdList );
            this.range = new Range(this.range.getStart(), other.range.getEnd() );
        }

        
        @Override
        public int compareTo(Change that) {
            if ( this.range.getStart() < that.range.getStart() ) {
                return -1;
            } else if ( this.range.getStart() > that.range.getStart() ) {
                return 1;
            } else {
                if ( this.range.getEnd() < that.range.getEnd() ) {
                    return -1;
                } else if ( this.range.getEnd() > that.range.getEnd() ) {
                    return 1;
                } 
            }
            return 0;
        }
        
        @Override
        public String toString() { 
            return "AlignIDs: "+this.alignIdList+" range: "+this.range.toString();
        }
    }
    
    /**
     * Task to asynchronously render the visualization
     */
    private class SideBySideTask implements BackgroundTask {
        private final String name;
        private BackgroundTaskStatus status;
        private final ComparisonSet set;
        private Date startDate;
        private Date endDate;
        
        public SideBySideTask(final String name, final ComparisonSet set) {
            this.name =  name;
            this.status = new BackgroundTaskStatus( this.name );
            this.set = set;
            this.startDate = new Date();
        }
        
        @Override
        public Type getType() {
            return BackgroundTask.Type.VISUALIZE;
        }
        
        @Override
        public void run() {
            try {
                LOG.info("Begin task "+this.name);
                this.status.begin();
                SideBySideView.this.render(set);
                LOG.info("Task "+this.name+" COMPLETE");
                this.endDate = new Date();   
                this.status.finish();
            } catch (IOException e) {
                LOG.error(this.name+" task failed", e.toString());
                this.status.fail(e.toString());
                this.endDate = new Date();
            } catch ( BackgroundTaskCanceledException e) {
                LOG.info( this.name+" task was canceled");
                this.endDate = new Date();
            } catch (Exception e) {
                LOG.error(this.name+" task failed", e);
                this.status.fail(e.toString());
                this.endDate = new Date();       
            }
        }
        
        @Override
        public void cancel() {
            this.status.cancel();
        }

        @Override
        public BackgroundTaskStatus.Status getStatus() {
            return this.status.getStatus();
        }

        @Override
        public String getName() {
            return this.name;
        }
        
        @Override
        public Date getEndTime() {
            return this.endDate;
        }
        
        @Override
        public Date getStartTime() {
            return this.startDate;
        }
        
        @Override
        public String getMessage() {
            return this.status.getNote();
        }
    }
}
