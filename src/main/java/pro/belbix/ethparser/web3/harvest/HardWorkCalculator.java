package pro.belbix.ethparser.web3.harvest;

import org.springframework.stereotype.Service;

import pro.belbix.ethparser.dto.v0.HardWorkDTO;
import pro.belbix.ethparser.dto.v0.HarvestDTO;
import pro.belbix.ethparser.repositories.v0.HardWorkRepository;
import pro.belbix.ethparser.repositories.v0.HarvestRepository;
import pro.belbix.ethparser.web3.EthBlockService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class HardWorkCalculator {

    private final double ETH_ESTIMATE = 0.1;
    private final HarvestRepository harvestRepository;
    private final HardWorkRepository hardworkRepository;
    private final EthBlockService ethBlockService;

    public HardWorkCalculator(HarvestRepository harvestRepository, HardWorkRepository hardWorkRepository, EthBlockService ethBlockService) {
        this.harvestRepository = harvestRepository;
        this.hardworkRepository = hardWorkRepository;
        this.ethBlockService = ethBlockService;
    }

    public double calculateTotalHardWorksFeeByOwner(String ownerAddress) {
        long lastBlock = ethBlockService.getLastBlock();
        long lastBlockDate = ethBlockService.getTimestampSecForBlock(null, lastBlock);
        
        HashMap<String, ArrayList<long[]>> blockRangeByVault = new HashMap<>();
        List<HarvestDTO> harvests = harvestRepository.fetchAllByOwner(ownerAddress, 0, lastBlockDate);
        
        harvests
            .stream()
            .filter(harvest -> !harvest.getVault().startsWith("PS"))
            .forEach(harvest -> {
                String vault = harvest.getVault();
                double balance = harvest.getOwnerBalance();
    
                if (!blockRangeByVault.containsKey(vault)) {
                    if (balance == 0) {
                        return;
                    }
                    ArrayList<long[]> blockPeriods = new ArrayList<>();
                    blockRangeByVault.put(vault, blockPeriods);
                }
    
                updateBlockPeriods(blockRangeByVault.get(vault), harvest);
            });

        return blockRangeByVault
            .entrySet()
            .stream()
            .map(entry -> {
                String vault = entry.getKey();

                ArrayList<long[]> blockPeriods = entry.getValue();
                long[] period = calculatePeriod(blockPeriods, lastBlockDate);

                List<HardWorkDTO> allHardWorksByVaultAndPeriod = hardworkRepository.findAllByVaultOrderByBlockDate(vault, period[0], period[1]);
                return blockPeriods
                    .stream()
                    .map(blockPeriod -> {
                        return allHardWorksByVaultAndPeriod
                            .stream()
                            .filter(hardwork -> {
                                Long blockStartDate = blockPeriod[0];
                                Long blockEndDate = lastBlockDate;
                                if (blockPeriod[1] > 0) {
                                    blockEndDate = blockPeriod[1];
                                }
                                return hardwork.getBlockDate() >= blockStartDate && hardwork.getBlockDate() <= blockEndDate;
                            })
                            .collect(Collectors.toList());
                    })
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            })
            .flatMap(Collection::stream)
            .map(hardwork -> hardwork.getEthPrice() * ETH_ESTIMATE)
            .reduce(0D, Double::sum);
    }

    private long[] calculatePeriod(ArrayList<long[]> blockPeriods, long lastBlockDate) {
        long[] period = new long[2];
        long startDate = blockPeriods.get(0)[0];
        long[] lastBlockPeriod = blockPeriods.get(blockPeriods.size() - 1);
        long endDate = lastBlockPeriod[1];
        if (endDate == 0) {
            endDate = lastBlockDate;
        }

        period[0] = startDate;
        period[1] = endDate;

        return period;
    }

    private long[] createBlockPeriod(HarvestDTO harvest) {
        long startBlockDate = harvest.getBlockDate();
        long[] blockPeriod = new long[2];
        blockPeriod[0] = startBlockDate;
        return blockPeriod;
    }

    private void updateBlockPeriods(ArrayList<long[]> blockPeriods, HarvestDTO harvest) {
        if (blockPeriods.size() == 0) {
            long[] blockPeriod = createBlockPeriod(harvest);
            blockPeriods.add(blockPeriod);
            return;
        }

        long[] lastBlockPeriod = blockPeriods.get(blockPeriods.size() - 1);
        double balance = harvest.getOwnerBalance();

        if (lastBlockPeriod[1] == 0 && balance == 0) {
            long endBlockDate = harvest.getBlockDate();
            lastBlockPeriod[1] = endBlockDate;
        }

        if (lastBlockPeriod[1] > 0 && balance > 0) {
            long[] blockPeriod = createBlockPeriod(harvest);
            blockPeriods.add(blockPeriod);
        }
    }

}
