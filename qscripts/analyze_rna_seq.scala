package org.broadinstitute.sting.queue.qscripts

import org.broadinstitute.sting.queue.QScript
import org.broadinstitute.sting.queue.util.QScriptUtils
import net.sf.samtools.SAMFileHeader.SortOrder
import org.broadinstitute.sting.utils.exceptions.UserException
import org.broadinstitute.sting.commandline.Hidden
import org.broadinstitute.sting.queue.extensions.picard.{ ReorderSam, SortSam, AddOrReplaceReadGroups, MarkDuplicates }
import org.broadinstitute.sting.queue.extensions.samtools._
import org.broadinstitute.sting.queue.extensions.gatk._
import org.broadinstitute.sting.queue.extensions.yeo._
import org.broadinstitute.sting.queue.util.TsccUtils._

class AnalyzeRNASeq extends QScript {
  // Script argument
  @Input(doc = "input file or txt file of input files")
  var input: File = _

  @Argument(doc = "adapter to trim", required = false)
  var adapter: List[String] = Nil

  @Argument(doc = "R1 3' Adapter Trim (cutadapt wrapper)", required = false)
  var a_adapter: List[String] = Nil

  @Argument(doc = "R2 3' Adapter Trim (cutadapt wrapper)", required = false)
  var A_adapter2: List[String] = Nil

  @Argument(doc = "R1 5' Adapter Trim (cutadapt wrapper)", required = false)
  var g_adapter: List[String] = Nil

  @Argument(doc = "R2 5' Adapter Trim (cutadapt wrapper)", required = false)
  var G_adapter2: List[String] = Nil

  @Argument(doc = "flipped", required = false)
  var flipped: String = "none"

  @Argument(doc = "not stranded", required = false)
  var not_stranded: Boolean = false

  @Argument(doc = "strict triming run")
  var strict: Boolean = false

  @Argument(doc = "start processing run from bam file")
  var fromBam: Boolean = false

  @Argument(doc = "location to place trackhub (must have the rest of the track hub made before starting script)")
  var location: String = "rna_seq"

  @Argument(doc = "reads are single ended", shortName = "single_end", fullName = "single_end", required = false)
  var singleEnd: Boolean = true

  class mv(@Input inBam: File, @Output outBam: File) extends CommandLineFunction {
  override def shortDescription = "SortSam"
  def commandLine = "cp " +
    required(inBam) + required(outBam)
  }
  case class sortSam(inSam: File, outBam: File, sortOrderP: SortOrder) extends SortSam {
    override def shortDescription = "sortSam"
    this.wallTime = Option((8 * 60 * 60).toLong)
    this.input :+= inSam
    this.output = outBam
    this.sortOrder = sortOrderP
    this.createIndex = true
  }

//  case class mapRepetitiveRegions(noAdapterFastq: File, filteredResults: File, filteredFastq: File) extends MapRepetitiveRegions {
//    override def shortDescription = "MapRepetitiveRegions"

//    this.inFastq = noAdapterFastq
//    this.outRep = filteredResults
//    this.outNoRep = filteredFastq
//    this.isIntermediate = true
//  }

  case class fastQC(fastq: File, dir: String= null) extends FastQC {
    this.inFastq = fastq
    this.outDir = dir
  }
  
  case class genomeCoverageBed(input: File, outBed: File, cur_strand: String, species: String) extends GenomeCoverageBed {
    this.inBam = input
    this.genomeSize = chromSizeLocation(species)
    this.bedGraph = outBed
    this.strand = cur_strand
    this.split = true
  }

  case class oldSplice(input: File, out: File, species: String) extends OldSplice {
    this.inBam = input
    this.out_file = out
    this.in_species = species
    this.splice_type = List("MXE", "SE")
    this.flip = (flipped == "flip")
  }
  case class singleRPKM(input: File, output: File, s: String) extends SingleRPKM {
    this.inCount = input
    this.outRPKM = output
  }

  case class countTags(input: File, index: File, output: File, species: String) extends CountTags {
    this.inBam = input
    this.outCount = output
    this.tags_annotation = exonLocation(species)
    this.flip = flipped
  }

  case class sailfish(input: File, species: String, ifStranded: Boolean = false, paired: File = null) extends Sailfish {
    this.inFastq = input
    this.stranded = ifStranded

    if (paired != null) {
      this.inFastqPair = paired
    }
    this.outDir = swapExt(swapExt(this.inFastq, ".gz", ""), ".fastq", ".sailfish")
    this.shScript = swapExt(this.outDir, ".sailfish", ".sailfish.sh")
    this.index = sailfishGenomeIndexLocation(species)

  }

  case class star(input: File, 
		  output: File, 
		  stranded: Boolean, 
		  genome_location: String,
		  paired: File = null,
		  fastq_out: File = null
		  ) extends STAR {
    
    this.inFastq = input

    if (paired != null) {
      this.inFastqPair = paired
    }

    this.outSam = output
    //intron motif should be used if the data is not stranded
    this.intronMotif = stranded
    this.genome = genome_location
    this.outFastq = fastq_out
  }

  case class addOrReplaceReadGroups(inBam: File, outBam: File) extends AddOrReplaceReadGroups {
    override def shortDescription = "AddOrReplaceReadGroups"
    this.wallTime = Option((4 * 60 * 60).toLong)
    this.input = List(inBam)
    this.output = outBam
    this.RGLB = "foo" //should record library id
    this.RGPL = "illumina"
    this.RGPU = "bar" //can record barcode information (once I get bardoding figured out)
    this.RGSM = "baz"
    this.RGCN = "Biogem" //need to add switch between biogem and singapore
    this.RGID = "foo"

  }

  case class parseOldSplice(inSplice: List[File], species: String) extends ParseOldSplice {
    override def shortDescription = "ParseOldSplice"
    this.inBam = inSplice
    this.in_species = species
  }

  case class samtoolsIndexFunction(input: File, output: File) extends SamtoolsIndexFunction {
    override def shortDescription = "indexBam"
    this.wallTime = Option((2 * 60 * 60).toLong)
    this.bamFile = input
    this.bamFileIndex = output
  }

  case class cutadapt(fastqFile: File, noAdapterFastq: File, adapterReport: File, adapter: List[String], pairedFile: File = null, pairedOut: File = null, a_adapters: List[String] = Nil, A_adapters: List[String] = Nil, g_adapters: List[String] = Nil, G_adapters: List[String] = Nil) extends Cutadapt {
    override def shortDescription = "cutadapt"

    this.inFastq = fastqFile
    this.outFastq = noAdapterFastq
    
    if(pairedFile != null) { 
      this.inPair = pairedFile
      this.outPair = pairedOut
    }
    this.three_prime = a_adapters
    this.three_prime2 = A_adapters
    this.five_prime = g_adapters
    this.five_prime2 = G_adapters
    this.report = adapterReport
    this.anywhere = adapter ++ List("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT")
    this.overlap = 5
    this.length = 18
    this.quality_cutoff = 6
    this.isIntermediate = true
  }

  case class makeRNASeQC(input: List[File], output: File) extends MakeRNASeQC {
    this.inBam = input
    this.out = output

  }
  case class runRNASeQC(in: File, out: String, single_end: Boolean, species: String) extends RunRNASeQC {
    this.input = in
    this.gc = gcLocation(species)
    this.output = out
    this.genome = genomeLocation(species)
    this.gtf = gffLocation(species)
    this.singleEnd = single_end
  }

  def stringentJobs(fastqFile: File, pairedFile: File = null): (File, File) = {

    // run if stringent
    val noGzFastq = swapExt(fastqFile, ".gz", "")
    val noAdapterFastq = swapExt(noGzFastq, ".fastq", ".polyATrim.adapterTrim.fastq")
    val outRep = swapExt(noAdapterFastq, ".fastq", ".rep.bam")
   
    val filteredFastq = swapExt(outRep, "", "Unmapped.out.mate1")
    val adapterReport = swapExt(noAdapterFastq, ".fastq", ".metrics")
    
    var noGzPair: File = null
    var noAdapterPair: File = null
    var filteredPair: File = null
    
    if (pairedFile != null) {        
     noGzPair = swapExt(pairedFile, ".gz", "")
     noAdapterPair = swapExt(noGzPair, ".fastq", ".polyATrim.adapterTrim.fastq")
     filteredPair = swapExt(outRep, "", "Unmapped.out.mate2")
    }
        
    //filters out adapter reads
    add(cutadapt(fastqFile = fastqFile, 
		 noAdapterFastq = noAdapterFastq,
		 adapterReport = adapterReport,
		 adapter = adapter, 
		 a_adapters = a_adapter,
		 A_adapters = A_adapter2,
		 g_adapters = g_adapter,
		 G_adapters = G_adapter2,
		 pairedFile = pairedFile, 
		 pairedOut = noAdapterPair))
    
    add(star(input = noAdapterFastq,
	     paired = noAdapterPair,
             output = outRep,
	     stranded = not_stranded,
             genome_location = "/projects/ps-yeolab/genomes/RepBase18.05.fasta/STAR_fixed",
             fastq_out = filteredFastq))

    var countRepetitiveRegions = new CountRepetitiveRegions
    countRepetitiveRegions.inBam = outRep
    countRepetitiveRegions.outFile = swapExt(outRep, ".rep.bam", ".rmRep.metrics")
    add(countRepetitiveRegions)

    add(new fastQC(filteredFastq, dir=qSettings.runDirectory))

    if (filteredPair != null) {
      add(new fastQC(filteredPair, dir=qSettings.runDirectory))
    }
    
    return (filteredFastq, filteredPair)
  }

  def makeBigWig(inBam: File, species: String): (File, File) = {

    val bedGraphFilePos = swapExt(inBam, ".bam", ".pos.bg")
    val bedGraphFilePosNorm = swapExt(bedGraphFilePos, ".pos.bg", ".norm.pos.bg")
    val bigWigFilePosNorm = swapExt(bedGraphFilePosNorm, ".bg", ".bw")

    val bedGraphFileNeg = swapExt(inBam, ".bam", ".neg.bg")
    val bedGraphFileNegNorm = swapExt(bedGraphFileNeg, ".neg.bg", ".norm.neg.bg")
    val bedGraphFileNegNormInverted = swapExt(bedGraphFileNegNorm, ".bg", ".t.bg")
    val bigWigFileNegNormInverted = swapExt(bedGraphFileNegNormInverted, ".t.bg", ".bw")

    add(new genomeCoverageBed(input = inBam, outBed = bedGraphFilePos, cur_strand = "+", species = species))
    add(new NormalizeBedGraph(inBedGraph = bedGraphFilePos, inBam = inBam, outBedGraph = bedGraphFilePosNorm))
    add(new BedGraphToBigWig(bedGraphFilePosNorm, chromSizeLocation(species), bigWigFilePosNorm))

    add(new genomeCoverageBed(input = inBam, outBed = bedGraphFileNeg, cur_strand = "-", species = species))
    add(new NormalizeBedGraph(inBedGraph = bedGraphFileNeg, inBam = inBam, outBedGraph = bedGraphFileNegNorm))
    add(new NegBedGraph(inBedGraph = bedGraphFileNegNorm, outBedGraph = bedGraphFileNegNormInverted))
    add(new BedGraphToBigWig(bedGraphFileNegNormInverted, chromSizeLocation(species), bigWigFileNegNormInverted))
    return (bigWigFileNegNormInverted, bigWigFilePosNorm)

  }

  case class samtoolsMergeFunction(inBams: Seq[File], outBam: File) extends SamtoolsMergeFunction {
    override def shortDescription = "samtoolsMerge"
    this.inputBams = inBams
    this.outputBam = outBam
  }

  def downstream_analysis(bamFile: File, bamIndex: File, singleEnd: Boolean, species: String): File = {
    val NRFFile = swapExt(bamFile, ".bam", ".NRF.metrics")
    val countFile = swapExt(bamFile, "bam", "count")
    val RPKMFile = swapExt(countFile, "count", "rpkm")
    val oldSpliceOut = swapExt(bamFile, "bam", "splices")
    val misoOut = swapExt(bamFile, "bam", "miso")
    val rnaEditingOut = swapExt(bamFile, "bam", "editing")

    add(new CalculateNRF(inBam = bamFile, genomeSize = chromSizeLocation(species), outNRF = NRFFile))

    val (bigWigFilePos: File, bigWigFileNeg: File) = makeBigWig(bamFile, species = species)

    add(new countTags(input = bamFile, index = bamIndex, output = countFile, species = species))
    add(new singleRPKM(input = countFile, output = RPKMFile, s = species))

    add(oldSplice(input = bamFile, out = oldSpliceOut, species = species))
    add(new Miso(inBam = bamFile, indexFile = bamIndex, species = species, pairedEnd = false, output = misoOut))
    //add(new RnaEditing(inBam = bamFile, snpEffDb = species, snpDb = snpDbLocation(species), genome = genomeLocation(species), flipped = flipped, output = rnaEditingOut))
    return oldSpliceOut
  }

  def script() {
    val fileList = QScriptUtils.createArgsFromFile(input)
    var posTrackHubFiles: List[File] = List()
    var negTrackHubFiles: List[File] = List()
    var splicesFiles: List[File] = List()
    var bamFiles: List[File] = List()
    var speciesList: List[String] = List()
    for ((groupName, valueList) <- (fileList groupBy (_._3))) {
      var combinedBams: Seq[File] = List()
      var genome: String = valueList(0)._2

      for (item: Tuple6[File, String, String, String, String, String] <- valueList) {
        var fastqFiles = item._1.toString().split(""";""")
        var species = item._2
        var fastqFile: File = new File(fastqFiles(0))
	
        var fastqPair: File = null
        var singleEnd = true
        var samFile: File = null
        if (!fromBam) {
          if (fastqFiles.length == 2) {
            singleEnd = false
            fastqPair = new File(fastqFiles(1))
            add(new fastQC(fastqPair, dir=qSettings.runDirectory))
          }
	
          add(new fastQC(fastqFile, dir=qSettings.runDirectory))

          
          val (filteredFastq, filteredPair) = stringentJobs(fastqFile, fastqPair)
	
          samFile = swapExt(filteredFastq, ".rep.bamUnmapped.out.mate1", ".rmRep.bam")
	  add(new sailfish(filteredFastq, species, !not_stranded, fastqPair))
          add(new star(input=filteredFastq, 
		       output=samFile, 
		       stranded=not_stranded, 
		       paired=filteredPair, 
		       genome_location = starGenomeLocation(species)))


        } else {
          samFile = new File(fastqFiles(0))
        }

        val sortedBamFile = swapExt(samFile, ".bam", ".sorted.bam")
        val rgSortedBamFile = swapExt(sortedBamFile, ".bam", ".rg.bam")
        val indexedBamFile = swapExt(rgSortedBamFile, "", ".bai")

        bamFiles = bamFiles ++ List(rgSortedBamFile)
        speciesList = speciesList ++ List(species)

        add(new sortSam(samFile, sortedBamFile, SortOrder.coordinate))
        add(addOrReplaceReadGroups(sortedBamFile, rgSortedBamFile))
        add(new samtoolsIndexFunction(rgSortedBamFile, indexedBamFile))

        combinedBams = combinedBams ++ List(rgSortedBamFile)

        var oldSpliceOut = downstream_analysis(rgSortedBamFile, indexedBamFile, singleEnd, genome)
        splicesFiles = splicesFiles ++ List(oldSpliceOut)
      }

      if (groupName != "null") {
        var mergedBams = new File(groupName + ".bam")
        val mergedIndex = swapExt(mergedBams, "", ".bai")
        if (combinedBams.length > 1) {
	  add(samtoolsMergeFunction(combinedBams, mergedBams))
	} else {
	  add(new mv(combinedBams(0), mergedBams))
	}
	add(new samtoolsIndexFunction(mergedBams, mergedIndex))
        
        var oldSpliceOut = downstream_analysis(mergedBams, mergedIndex, singleEnd, genome)
        splicesFiles = splicesFiles ++ List(oldSpliceOut)
      }
    }

    def tuple2ToList[T](t: (T, T)): List[T] = List(t._1, t._2)
    for ((species, files) <- posTrackHubFiles zip negTrackHubFiles zip speciesList groupBy { _._2 }) {
      var trackHubFiles = files map { _._1 } map tuple2ToList reduceLeft { (a, b) => a ++ b }
      add(new MakeTrackHub(trackHubFiles, location = location + "_" + species, genome = species))
    }

    for ((species, files) <- speciesList zip splicesFiles groupBy { _._1 }) {
      add(parseOldSplice(files map { _._2 }, species = species))
    }

    for ((species, files) <- speciesList zip bamFiles groupBy { _._1 }) {
      var rnaseqc = new File("rnaseqc_" + species + ".txt")
      add(new makeRNASeQC(input = files map { _._2 }, output = rnaseqc))
      add(new runRNASeQC(in = rnaseqc, out = "rnaseqc_" + species, single_end = singleEnd, species = species))
    }
  }
}

