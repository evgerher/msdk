/*
 * (C) Copyright 2015-2018 by MSDK Development Team
 *
 * This software is dual-licensed under either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1 as published by the Free
 * Software Foundation
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by the Eclipse Foundation.
 */

package io.github.msdk.id.sirius;

import de.unijena.bioinf.ChemistryBase.chem.FormulaConstraints;
import de.unijena.bioinf.ChemistryBase.chem.MolecularFormula;
import de.unijena.bioinf.ChemistryBase.chem.MutableMolecularFormula;
import de.unijena.bioinf.ChemistryBase.chem.PrecursorIonType;
import de.unijena.bioinf.ChemistryBase.chem.utils.MolecularFormulaPacker;
import de.unijena.bioinf.ChemistryBase.fp.ProbabilityFingerprint;
import de.unijena.bioinf.ChemistryBase.ms.Deviation;
import de.unijena.bioinf.ChemistryBase.ms.MutableMs2Experiment;
import de.unijena.bioinf.ChemistryBase.ms.MutableMs2Spectrum;
import de.unijena.bioinf.ChemistryBase.ms.Peak;
import de.unijena.bioinf.ChemistryBase.ms.Spectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;
import de.unijena.bioinf.sirius.IdentificationResult;
import de.unijena.bioinf.sirius.IsotopePatternHandling;
import de.unijena.bioinf.sirius.Sirius;

import io.github.msdk.MSDKException;
import io.github.msdk.MSDKMethod;
import io.github.msdk.MSDKRuntimeException;
import io.github.msdk.datamodel.IonAnnotation;
import io.github.msdk.datamodel.IonType;
import io.github.msdk.datamodel.MsSpectrum;
import io.github.msdk.datamodel.SimpleIonAnnotation;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.openscience.cdk.interfaces.IMolecularFormula;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.tools.manipulator.MolecularFormulaManipulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p> SiriusIdentificationMethod class. </p>
 *
 * This class wraps the Sirius module and transforms its results into MSDK data structures
 * Transformation of IdentificationResult (Sirius) into IonAnnatation (MSDK)
 */
public class SiriusIdentificationMethod implements MSDKMethod<List<IonAnnotation>> {

  /**
   * Dynamic loading of native libraries
   */
  static {
      try {
      // GLPK requires two libraries
      String[] libs = {"glpk_4_60", "glpk_4_60_java"};
      NativeLibraryLoader.loadLibraryFromJar("glpk-4.60", libs);
    } catch (Exception ex) {
      throw new MSDKRuntimeException(ex);
    }
  }

  private static final Logger logger = LoggerFactory.getLogger(SiriusIdentificationMethod.class);
  private final Sirius sirius;
  private final List<MsSpectrum> ms1;
  private final List<MsSpectrum> ms2;
  private final Double parentMass;
  private final IonType ion;
  private final int numberOfCandidates;
  private final FormulaConstraints constraints;
  private final Deviation deviation;
  private boolean cancelled = false;
  private List<IonAnnotation> result;

  /* Set to be class elements for performance */

  /**
   * <p> Constructor for SiriusIdentificationMethod class. </p>
   *
   * @param ms1 - can be null! MsSpectrum level 1
   * @param ms2 - MsSpectrum level 2
   * @param parentMass - Most intensive usually or specified
   * @param ion - Ionization
   * @param numberOfCandidates - amount of IdentificationResults to be returned from Sirius
   * @param constraints - FormulaConstraints provided by the end user. Can be created using ConstraintsGenerator
   * @param deviation - float value of possible mass deviation
   */
  public SiriusIdentificationMethod(@Nullable List<MsSpectrum> ms1, @Nullable List<MsSpectrum> ms2, Double parentMass,
      IonType ion, int numberOfCandidates, @Nullable FormulaConstraints constraints, Double deviation) {
    if (ms1 == null && ms2 == null) throw new MSDKRuntimeException("Only one of MS && MS/MS can be null at a time");

    sirius = new Sirius();
    this.ms1 = ms1;
    this.ms2 = ms2;
    this.parentMass = parentMass;
    this.ion = ion;
    this.numberOfCandidates = numberOfCandidates;
    this.constraints = constraints;
    this.deviation = new Deviation(deviation);
  }

  public double getParentMass() {
    return parentMass;
  }

  public IonType getIonization() {
    return ion;
  }

  public List<MsSpectrum> getMsSpectra() {
    return ms1;
  }

  public List<MsSpectrum> getMs2Spectra() {
    return ms2;
  }

  public int getNumberOfCandidates() {
    return numberOfCandidates;
  }

  /**
   * <p> Method for processing spectra by Sirius module </p>
   * Transformation of MSDK data structures into Sirius structures and processing by Sirius
   * Method is left to be protected for test coverage
   */
  protected List<IdentificationResult> siriusProcessSpectra() throws MSDKException {
    MutableMs2Experiment experiment = loadInitialExperiment();
    loadSpectra(experiment);
    configureExperiment(experiment);

    logger.debug("Sirius starts processing MsSpectrums");
    List<IdentificationResult> siriusResults  = sirius.identify(experiment,
        numberOfCandidates, true, IsotopePatternHandling.both, constraints);
    logger.debug("Sirius finished processing and returned {} results", siriusResults.size());

    return siriusResults;
  }

  /**
   * <p>Method for configuration of the Sirius experiment</p>
   * Method loads several paramenters with sirius object, such as:
   * Deviation - mass deviation
   * Recalibrarion - flag of object recalibration after processing
   * Constraints - user-defined search-space for the formula
   * @param experiment - instance of Ms2Experiment with loaded MS & MS/MS spectra
   */
  private void configureExperiment(MutableMs2Experiment experiment) {
    /* Manual setting up annotations, because sirius.identify does not use some of the fields */
    sirius.setAllowedMassDeviation(experiment, deviation);
    sirius.enableRecalibration(experiment, true);
    sirius.setIsotopeMode(experiment, IsotopePatternHandling.both);

    /* Constraints are loaded twice (here and in .identify method), because .identify method does not notice it */
    if (constraints != null)
      sirius.setFormulaConstraints(experiment, constraints);
  }

  /**
   * <p>Method for initialization of empty Ms2Experiment</p>
   * There is a trick for initialization of empty experiment, it is done for cases when there is only ms1 spectra
   * It loads an empty Spectrum<Peak> into experiment and removes it after initialization.
   * @return
   */
  private MutableMs2Experiment loadInitialExperiment() {
    String ionization = ion.getName();
    PrecursorIonType precursor = sirius.getPrecursorIonType(ionization);
    /* Initialization of empty Sirius spectrum */
    Spectrum<Peak> emptySiriusMs2 = new MutableMs2Spectrum();

    /* MutableMs2Experiment allows to specify additional fields and it is exactly what comes from .getMs2Experiment */
    MutableMs2Experiment experiment = (MutableMs2Experiment) sirius.getMs2Experiment(parentMass, precursor, null, emptySiriusMs2);
    experiment.getMs2Spectra().clear();
    return experiment;
  }

  /**
   * <p> Method for transformation of MsSpectrum into Spectrum<Peak></p>
   * Method transforms MSDK object into Sirius object
   * @param msdkSpectrum - non-null MsSpectrum object.
   * @return new Spectrum<Peak>
   */
  private Spectrum<Peak> transformSpectrum(@Nonnull MsSpectrum msdkSpectrum) {
    double mz[], intensity[];
    mz = msdkSpectrum.getMzValues();
    intensity = LocalArrayUtil.convertToDoubles(msdkSpectrum.getIntensityValues());
    return sirius.wrapSpectrum(mz, intensity);
  }

  /**
   * <p> Method for loading MS & MS/MS spectra</p>
   * Method loads MS and MS/MS spectra into Ms2Experiment object
   * @param experiment - experiment Sirius works with and where to load spectra
   */
  private void loadSpectra(MutableMs2Experiment experiment) {
    List<MutableMs2Spectrum> ms2spectra = experiment.getMs2Spectra();
    List<SimpleSpectrum> ms1spectra = experiment.getMs1Spectra();

    if (ms1 != null) {
      for (MsSpectrum msdkSpectrum : ms1) {
        Spectrum<Peak> peaks = transformSpectrum(msdkSpectrum);
        ms1spectra.add(new SimpleSpectrum(peaks));
      }
    }

    if (ms2!= null && ms2.size() > 0) {
      for (MsSpectrum msdkSpectrum : ms2) {
        Spectrum<Peak> peaks = transformSpectrum(msdkSpectrum);
      /* MutableMs2Experiment does not accept Ms1 as a Spectrum<Peak>, so there is a new object */
        MutableMs2Spectrum siriusMs2 = new MutableMs2Spectrum(peaks);
        ms2spectra.add(siriusMs2);
      }
    }
  }

  @Nullable
  @Override
  public Float getFinishedPercentage() {
    return null;
  }

  @Nullable
  @Override
  public List<IonAnnotation> execute() throws MSDKException {
    logger.info("Started execution of SiriusIdentificationMethod");

    result = new ArrayList<>();
    List<IdentificationResult> siriusSpectra = siriusProcessSpectra();

    for (IdentificationResult r : siriusSpectra) {
      if (cancelled)
        return null;
      IonAnnotation ionAnnotation = createIonAnnotation(r);
      result.add(ionAnnotation);
    }

    logger.info("Finished execution of SiriusIdentificationMethod");
    return result;
  }

  /**
   * <p>Method configures the IonAnnotation according to IdentificationResult object</p>
   * @param siriusResult - IdentificationResult object from Sirius
   * @return configured instance of SimpleIonAnnotation
   */
  private IonAnnotation createIonAnnotation(IdentificationResult siriusResult) {
    SiriusIonAnnotation ionAnnotation = new SiriusIonAnnotation();
    IMolecularFormula formula = generateFormula(siriusResult);
    ionAnnotation.setFormula(formula);
    ionAnnotation.setIdentificationMethod("Sirius");
    ionAnnotation.setIonType(ion);
    ionAnnotation.setFTree(siriusResult.getResolvedTree());

    return ionAnnotation;
  }

  /**
   * <p>Method generates IMolecularFormula according to IdentificationResult object</p>
   * @param siriusResult - IdentificationResult object from Sirius
   * @return new IMolecularFormula instance
   */
  private IMolecularFormula generateFormula(IdentificationResult siriusResult) {
    int charge = siriusResult.getResolvedTree().getAnnotationOrThrow(PrecursorIonType.class).getCharge();
    String formula = siriusResult.getMolecularFormula().toString();
    IMolecularFormula iFormula = MolecularFormulaManipulator
        .getMolecularFormula(formula,
            SilentChemObjectBuilder.getInstance());
    iFormula.setCharge(charge);

    return iFormula;
  }

  @Nullable
  @Override
  public List<IonAnnotation> getResult() {
    return result;
  }

  @Override
  public void cancel() {
    cancelled = true;
  }
}
