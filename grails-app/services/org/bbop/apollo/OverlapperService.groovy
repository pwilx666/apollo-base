package org.bbop.apollo

import grails.transaction.Transactional
import org.bbop.apollo.sequence.Overlapper
import org.bbop.apollo.sequence.Strand

@Transactional(readOnly = true)
class OverlapperService implements Overlapper{


    def transcriptService
    def exonService 
    def configWrapperService 

    @Override
    boolean overlaps(Transcript transcript, Gene gene) {
        //log.debug("overlaps(Transcript transcript, Gene gene) ")
        String overlapperName = configWrapperService.overlapper.class.name
        if(overlapperName.contains("Orf")){
            return overlapsOrf(transcript,gene)
        }
        throw new AnnotationException("Only ORF overlapper supported right now")
    }

    @Override
    boolean overlaps(Transcript transcript1, Transcript transcript2) {
        //log.debug("overlaps(Transcript transcript1, Transcript transcript2) ")
        String overlapperName = configWrapperService.overlapper.class.name

        
        if(overlapperName.contains("Orf")){
            return overlapsOrf(transcript1,transcript2)
        }
        throw new AnnotationException("Only ORF overlapper supported right now")
    }


    boolean overlapsOrf(Transcript transcript, Gene gene) {
        //log.debug("overlapsOrf(Transcript transcript, Gene gene) ")
        long start = System.currentTimeMillis();
        for (Transcript geneTranscript : transcriptService.getTranscripts(gene)) {
            if (overlapsOrf(transcript, geneTranscript)) {
                println "@Duration for cdsOverlap: ${System.currentTimeMillis() - start}"
                return true;
            }
        }
        println "@Duration for cdsOverlap: ${System.currentTimeMillis() - start}"
        return false;
    }

    boolean overlapsOrf(Transcript transcript1, Transcript transcript2) {
//        log.debug("overlapsOrf(Transcript transcript1, Transcript transcript2) ")
        if ((transcriptService.isProteinCoding(transcript1) && transcriptService.isProteinCoding(transcript2))
                && ((transcriptService.getGene(transcript1) == null || transcriptService.getGene(transcript2) == null) || (!(transcriptService.getGene(transcript1) instanceof Pseudogene) && !(transcriptService.getGene(transcript2) instanceof Pseudogene)))) {

            CDS cds = transcriptService.getCDS(transcript1);

            if (overlaps(cds,transcriptService.getCDS(transcript2)) &&  (overlaps(transcriptService.getCDS(transcript2),cds)))  {
                List<Exon> exons1 = exonService.getSortedExons(transcript1);
                List<Exon> exons2 = exonService.getSortedExons(transcript2);
                return cdsOverlap(exons1, exons2, true);
            }
        }
        return false
    }

    private class CDSEntity {
        // POGO for handling CDS of individual exons
        int fmin;
        int fmax;
        int length;
        int phase;
        String name;
        String uniqueName;
        Sequence sequence;
        int strand;
    }
    
    boolean overlaps(CDSEntity cds1, CDSEntity cds2) {
        //overlaps() method for POGO CDSEntity
        return overlaps(cds1.fmin, cds1.fmax, cds2.fmin, cds2.fmax)
    }
    
    private ArrayList<CDSEntity> getCDSEntities(CDS cds, List<Exon> exons) {
        ArrayList<CDSEntity> cdsEntities = new ArrayList<CDSEntity>();
        HashMap<String,String> exonFrame = new HashMap<String,String>();
        for (Exon exon : exons) {
            if (!overlaps(exon,cds)) {
                continue
            }
            int fmin = exon.fmin < cds.fmin ? cds.fmin : exon.fmin
            int fmax = exon.fmax > cds.fmax ? cds.fmax : exon.fmax
            int cdsEntityLength = fmax - fmin
            
            CDSEntity cdsEntity = new CDSEntity();
            cdsEntity.fmin = fmin;
            cdsEntity.fmax = fmax;
            cdsEntity.length = cdsEntityLength;
            cdsEntity.name = cds.name;
            cdsEntity.uniqueName = cds.uniqueName + "-cds-entity"
            cdsEntity.sequence = cds.featureLocation.sequence
            cdsEntity.strand = cds.strand
            cdsEntities.add(cdsEntity);
        }
        return cdsEntities;
    }
    
    private boolean cdsOverlap(List<Exon> exons1, List<Exon> exons2, boolean checkStrand) {
        // implementation for determining isoforms based on CDS overlaps
        CDS cds1 = transcriptService.getCDS( exonService.getTranscript(exons1.get(0)) )
        CDS cds2 = transcriptService.getCDS( exonService.getTranscript(exons2.get(0)) )
        ArrayList<CDSEntity> cdsEntitiesForTranscript1 = getCDSEntities(cds1, exons1)
        ArrayList<CDSEntity> cdsEntitiesForTranscript2 = getCDSEntities(cds2, exons2)
        int cds1UniversalFrame = 0
        int cds2UniversalFrame = 0

        for (int i = 0; i < cdsEntitiesForTranscript1.size(); i++) {
            CDSEntity c1 = cdsEntitiesForTranscript1.get(i)
            cds1UniversalFrame = (cds1UniversalFrame + c1.length) % 3
            for (int j = 0; j < cdsEntitiesForTranscript2.size(); j++) {
                CDSEntity c2 = cdsEntitiesForTranscript2.get(j)
                cds2UniversalFrame = (cds2UniversalFrame + c2.length) % 3
                log.debug "Comparing CDSEntity ${c1.fmin}-${c1.fmax} to ${c2.fmin}-${c2.fmax}"
                log.debug "CDS1 vs. CDS2 universal frame: ${cds1UniversalFrame} vs. ${cds2UniversalFrame}"
                if (overlaps(c1,c2)) {
                    if (checkStrand) {
                        if ((c1.strand == c2.strand) && (cds1UniversalFrame == cds2UniversalFrame)) {
                            log.debug "Conditions met"
                            return true
                        }
                    }
                    else {
                        return true
                    }
                }
            }
        }
        return false
    }
    
    boolean overlaps(Feature leftFeature, Feature rightFeature, boolean compareStrands = true) {
        //log.debug("overlaps(Feature leftFeature, Feature rightFeature, boolean compareStrands)")
        return overlaps(leftFeature.featureLocation, rightFeature.featureLocation, compareStrands)
    }

    boolean overlaps(FeatureLocation leftFeatureLocation, FeatureLocation rightFeatureLocation, boolean compareStrands = true) {
        //log.debug("overlaps(FeatureLocation leftFeatureLocation, FeatureLocation rightFeatureLocation, boolean compareStrands)")
        if (leftFeatureLocation.sequence != rightFeatureLocation.sequence) {
            return false;
        }
        int thisFmin = leftFeatureLocation.getFmin();
        int thisFmax = leftFeatureLocation.getFmax();
        int thisStrand = leftFeatureLocation.getStrand();
        int otherFmin = rightFeatureLocation.getFmin();
        int otherFmax = rightFeatureLocation.getFmax();
        int otherStrand = rightFeatureLocation.getStrand();
        boolean strandsOverlap = compareStrands ? thisStrand == otherStrand : true;
        if (strandsOverlap &&
                overlaps(thisFmin,thisFmax,otherFmin,otherFmax)
                ) {
            return true;
        }
        return false;
    }

    boolean overlaps(int leftFmin, int leftFmax,int rightFmin,int rightFmax) {
        return (leftFmin <= rightFmin && leftFmax > rightFmin ||
                        leftFmin >= rightFmin && leftFmin < rightFmax)
    }


}
